package io.github.ayech14.mealiq.commands;

import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import java.util.List;

/** Several commands confirmed as one change; undo reverts them in reverse order. */
public class CompositeCommand implements PlanChangeCommand {

    private final List<PlanChangeCommand> commands;

    public CompositeCommand(List<? extends PlanChangeCommand> commands) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("A composite command needs at least one command");
        }
        this.commands = List.copyOf(commands);
    }

    @Override
    public void execute(MealPlan plan) {
        commands.forEach(c -> c.execute(plan));
    }

    @Override
    public void undo(MealPlan plan) {
        commands.reversed().forEach(c -> c.undo(plan));
    }

    @Override
    public String describe() {
        return String.join("; ", commands.stream().map(PlanChangeCommand::describe).toList());
    }

    @Override
    public List<MealSwap> swaps() {
        return commands.stream().flatMap(c -> c.swaps().stream()).toList();
    }
}
