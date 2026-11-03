package io.github.ayech14.mealiq.persistence.catalogue;

import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code catalogue/prices.csv}: average Canadian retail prices from Statistics Canada
 * table 18-10-0245-01, one package price per catalogue ingredient (built by
 * {@code scripts/build_prices.py}).
 */
public final class PriceCatalogueLoader {

    public static final String RESOURCE = "/catalogue/prices.csv";

    /** The price of one package of an ingredient, and where it comes from. */
    public record Entry(String ingredient, BigDecimal price, Quantity packageSize, String statcanProduct,
                        String referenceMonth, String note) {
    }

    private PriceCatalogueLoader() {
    }

    public static List<Entry> read() {
        try (InputStream in = PriceCatalogueLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new PersistenceException(RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PersistenceException("Cannot read " + RESOURCE, e);
        }
    }

    /** Rejects duplicate ingredients and non-positive prices or package sizes. */
    public static List<Entry> parse(String csv) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : CsvReader.readWithHeader(csv)) {
            String name = row.get("ingredient").strip();
            BigDecimal price = new BigDecimal(row.get("price").strip());
            Quantity size = Quantity.of(Double.parseDouble(row.get("package_amount")), row.get("package_unit"));
            if (!seen.add(name)) {
                throw new PersistenceException("Duplicate price for " + name);
            }
            if (price.signum() <= 0 || size.isZero()) {
                throw new PersistenceException("Price and package size must be positive for " + name);
            }
            entries.add(new Entry(name, price, size, row.get("statcan_product"), row.get("reference_month"),
                    row.get("note")));
        }
        return entries;
    }
}
