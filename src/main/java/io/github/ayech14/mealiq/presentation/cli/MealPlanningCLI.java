package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.config.ConfigException;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.jdbc.Database;
import io.github.ayech14.mealiq.persistence.jdbc.DatabaseSetup;
import io.github.ayech14.mealiq.services.InvalidInputException;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

/**
 * The command-line interface. Commands only parse input, call {@link MealPlanningFacade}
 * and print the result; every rule lives behind the facade (D02).
 *
 * <pre>./mealiq --help</pre>
 */
@Command(name = "mealiq", mixinStandardHelpOptions = true, version = "MealIQ 0.1.0",
        description = "Meal planning and grocery optimization. A planning aid, not medical or dietary advice.",
        subcommands = {ProfileCommand.class, PantryCommand.class, RecipesCommand.class, PlanCommand.class,
            GroceryCommand.class})
public final class MealPlanningCLI implements Runnable {

    static final int OK = 0;
    static final int FAILED = 1;

    @Option(names = "--profile", defaultValue = "default", scope = ScopeType.INHERIT,
            description = "Local profile name (default: ${DEFAULT-VALUE}).")
    String profileName;

    private final MealPlanningFacade facade;
    private final Supplier<DatabaseSetup.Result> databaseSetup;
    private final CommandLine commandLine;

    /** {@code databaseSetup} prepares the database for the {@code setup} command. */
    public MealPlanningCLI(MealPlanningFacade facade, Supplier<DatabaseSetup.Result> databaseSetup,
                           PrintWriter out, PrintWriter err) {
        this.facade = facade;
        this.databaseSetup = databaseSetup;
        this.commandLine = new CommandLine(this)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setOut(out)
                .setErr(err)
                .setExecutionExceptionHandler(this::report);
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            AppConfig config = AppConfig.load();
            MealPlanningCLI cli = new MealPlanningCLI(MealPlanningFacade.fromConfig(config),
                    () -> DatabaseSetup.run(Database.from(config)),
                    new PrintWriter(System.out, true), new PrintWriter(System.err, true));
            exitCode = cli.executeCommand(List.of(args));
        } catch (ConfigException e) {
            System.err.println("Configuration problem in .env: " + e.getMessage());
            exitCode = FAILED;
        }
        System.exit(exitCode);
    }

    public int executeCommand(List<String> args) {
        return commandLine.execute(args.toArray(String[]::new));
    }

    @Override
    public void run() {
        commandLine.usage(out());
    }

    @Command(name = "setup", description = "Create missing database tables and load the ingredient catalogue and recipes.")
    int setup() {
        DatabaseSetup.Result result = databaseSetup.get();
        out().println(result.tablesCreated().isEmpty() ? "All tables already exist."
                : "Created tables: " + String.join(", ", result.tablesCreated()));
        out().println("Ingredient catalogue: " + result.ingredientsLoaded() + " ingredients.");
        out().println("Seed recipes: " + result.recipesLoaded() + " recipes.");
        return OK;
    }

    @Command(name = "ingredients", description = "List catalogue ingredients, optionally only names containing TEXT.")
    int ingredients(@Parameters(arity = "0..1", paramLabel = "TEXT") String text) {
        List<Ingredient> found = facade.searchIngredients(text);
        if (found.isEmpty()) {
            out().println("No ingredient matches '" + text + "'.");
            return FAILED;
        }
        found.forEach(i -> out().printf("  %-24s %s%n", i.name(), i.category()));
        out().println(found.size() + " ingredient" + (found.size() == 1 ? "" : "s") + ".");
        return OK;
    }

    MealPlanningFacade facade() {
        return facade;
    }

    UUID profileId() {
        return MealPlanningFacade.localProfileId(profileName);
    }

    PrintWriter out() {
        return commandLine.getOut();
    }

    /** Turns expected failures into a short message and exit code 1, instead of a stack trace. */
    private int report(Exception error, CommandLine command, CommandLine.ParseResult parseResult) {
        PrintWriter err = command.getErr();
        switch (error) {
            case InvalidInputException invalid -> {
                err.println("Not saved:");
                invalid.errors().forEach((field, message) ->
                        err.println("  " + optionName(field) + ": " + message));
                if (invalid.errors().containsKey("ingredient")) {
                    err.println("Find catalogue names with: mealiq ingredients TEXT");
                }
            }
            case NotFoundException missing -> err.println(missing.getMessage());
            case PersistenceException database -> {
                err.println(database.getMessage());
                err.println("Check that PostgreSQL is running (docker compose up -d) and run: mealiq setup");
            }
            default -> {
                err.println("Unexpected error: " + error);
                error.printStackTrace(err);
            }
        }
        return FAILED;
    }

    private static final Map<String, String> OPTION_NAMES = Map.of(
            "dailyCalorieTarget", "--calories",
            "dailyProteinTargetG", "--protein",
            "dietaryRestrictions", "--diet",
            "allergies", "--allergies",
            "preferredFoods", "--likes",
            "dislikedFoods", "--dislikes",
            "mealsPerDay", "--meals-per-day",
            "weeklyBudget", "--budget",
            "maxCookMinutes", "--cook-minutes");

    /** The CLI option for a service field name, so errors point at what the user typed. */
    static String optionName(String field) {
        return OPTION_NAMES.getOrDefault(field, field);
    }
}
