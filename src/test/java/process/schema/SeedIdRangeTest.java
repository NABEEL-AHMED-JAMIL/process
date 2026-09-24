package process.schema;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-32: the seeded-id rule, where a new seed cannot break it unnoticed.
 *
 * A row a changeset inserts with a fixed id takes one from 1-999; every sequence starts at 1000 and owns the
 * rest, so no seed can ever collide with a row the application created. The old convention lived inside the
 * sequence's range (V2 at 1001-1019, V9 at 1020+ "to avoid" them) and was written down nowhere. Seeds are
 * Liquibase insert changes, so the ids are visible here; a hand-written INSERT would hide one, and is refused.
 */
class SeedIdRangeTest {

    /**
     * Changesets whose INSERT moves a value that already exists, into a table with no sequence -- so
     * there is no id to hide, which is what the rule guards. Each with its reason.
     */
    private static final Map<String, String> MOVES_NOT_SEEDS = Collections.singletonMap(
        "db/changelog/yaml/V88.0-orchestration-setting.yaml",
        "moves QUEUE_FETCH_LIMIT's value out of lookup_data (MIG-136); orchestration_setting is keyed by name, no sequence");

    @Test
    @SuppressWarnings("unchecked")
    void everySeededIdIsBelowEverySequence() throws Exception {
        List<String> outside = new ArrayList<>();
        List<String> handWritten = new ArrayList<>();
        int seeds = 0;
        for (Map<String, Object> entry : list(load("db/changelog/db.changelog-master.yaml"))) {
            String file = (String) ((Map<String, Object>) entry.get("include")).get("file");
            if (file.contains("V50.0-schema-baseline")) {
                continue;
            }
            if (text(file).toUpperCase().contains("INSERT INTO") && !MOVES_NOT_SEEDS.containsKey(file)) {
                handWritten.add(file);
            }
            for (Map<String, Object> item : list(load(file))) {
                Map<String, Object> changeSet = (Map<String, Object>) item.get("changeSet");
                if (changeSet == null) {
                    continue;
                }
                for (Map<String, Object> change : (List<Map<String, Object>>) changeSet.get("changes")) {
                    Map<String, Object> insert = (Map<String, Object>) change.get("insert");
                    if (insert == null) {
                        continue;
                    }
                    Map<String, Object> idColumn = (Map<String, Object>) ((List<Map<String, Object>>) insert.get("columns")).get(0).get("column");
                    Object id = idColumn.get("valueNumeric");
                    seeds++;
                    if (!String.valueOf(idColumn.get("name")).endsWith("_id") || id == null
                        || Long.parseLong(String.valueOf(id)) < 1 || Long.parseLong(String.valueOf(id)) > 999) {
                        outside.add(changeSet.get("id") + ": " + insert.get("tableName") + " " + idColumn);
                    }
                }
            }
        }
        assertThat(handWritten).as("seeds written as raw INSERTs").isEmpty();
        assertThat(outside).as("seeded ids outside 1-999, or not the insert's first column").isEmpty();
        assertThat(seeds).as("V70.2 and V70.5 seed four rows").isGreaterThanOrEqualTo(4);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> changelog) {
        return (List<Map<String, Object>>) changelog.get("databaseChangeLog");
    }

    private static Map<String, Object> load(String resource) throws Exception {
        try (InputStream stream = SeedIdRangeTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new Yaml().load(stream);
        }
    }

    private static String text(String resource) throws Exception {
        try (InputStream stream = SeedIdRangeTest.class.getClassLoader().getResourceAsStream(resource);
             Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }
}
