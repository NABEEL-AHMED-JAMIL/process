package process.schema;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-59: the rollback strategy, written where a new changeset cannot quietly break it.
 *
 * The decision: forward-only from the V50 baseline. V1-V49 are archived and no longer executed, so the V12-V14
 * rollback blocks that name tables V26-V30 dropped cannot be reached by any `liquibase rollback` -- they are
 * history, not a path. A deployment is undone by a forward changeset. Every changeset after V50 still
 * declares its rollback, or is listed below with the reason it has none, so an operator reading one knows
 * before running it whether `rollback` is a way back.
 */
class ChangelogRollbackDeclarationTest {

    /** Changesets deliberately without a rollback, and why. Adding one here is the decision, in review. */
    private static final Map<String, String> FORWARD_ONLY;

    static {
        Map<String, String> forwardOnly = new LinkedHashMap<>();
        forwardOnly.put("52.0-notification-moves-to-notifications-db", "drops an empty table whose data lives in notifications_db");
        forwardOnly.put("54.0-document-converter-task-moves-to-media-db", "drops an empty table whose data lives in media_db");
        forwardOnly.put("55.1-drop-recreated-platform-wide-alias-constraint", "drops a constraint ddl-auto re-created by mistake");
        forwardOnly.put("56.0-analytics-rows-carry-storage-connection-id", "addColumn only; Liquibase derives the rollback");
        forwardOnly.put("69.0-tenant-seed-backfills", "data backfills that matched no row when written (2026-09-24); undoing one "
            + "would un-assign rows nobody can tell apart from real ones");
        forwardOnly.put("69.1-identity-cutover", "runs only after the six tables moved to identity_db; the foreign keys it drops "
            + "cannot come back once a job names a workspace or person that exists only there");
        forwardOnly.put("56.1-stamp-platform-admin-analytics-rows", "a data backfill; the previous values were wrong");
        FORWARD_ONLY = Collections.unmodifiableMap(forwardOnly);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyChangesetAfterTheBaselineDeclaresItsRollbackOrWhyNot() throws Exception {
        List<String> undeclared = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> entry : this.list(this.load("db/changelog/db.changelog-master.yaml"))) {
            String file = (String) ((Map<String, Object>) entry.get("include")).get("file");
            for (Map<String, Object> item : this.list(this.load(file))) {
                Map<String, Object> changeSet = (Map<String, Object>) item.get("changeSet");
                if (changeSet == null) {
                    continue;
                }
                String id = String.valueOf(changeSet.get("id"));
                seen.add(id);
                if (!changeSet.containsKey("rollback") && !FORWARD_ONLY.containsKey(id)) {
                    undeclared.add(id + " (" + file + ")");
                }
            }
        }
        assertThat(undeclared).as("changesets with neither a rollback nor a recorded reason").isEmpty();
        assertThat(seen).as("every forward-only entry names a real changeset").containsAll(FORWARD_ONLY.keySet());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> changelog) {
        return (List<Map<String, Object>>) changelog.get("databaseChangeLog");
    }

    private Map<String, Object> load(String resource) throws Exception {
        try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new Yaml().load(stream);
        }
    }
}
