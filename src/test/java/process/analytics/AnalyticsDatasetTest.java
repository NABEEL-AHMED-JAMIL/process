package process.analytics;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.pojo.AnalyticsDataset;
import process.model.pojo.AuditListener;
import process.security.TenantContext;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the dataset row is allowed to remember.
 *
 * There is no service and no endpoint over this table yet, so the things worth pinning are the two
 * decisions that are expensive to reverse once saved queries, charts and benchmark results point
 * at it: that a dataset names a connection instead of copying where that connection points, and
 * that it belongs to exactly one workspace. Both are single words in an annotation, both look like
 * a harmless edit, and neither fails visibly when it is wrong -- a duplicated bucket is only wrong
 * on the day a connection is repointed, and a loosened filter is only wrong to the tenant reading
 * somebody else's rows.
 *
 * @author Nabeel Ahmed
 */
public class AnalyticsDatasetTest {

    /**
     * Anything that would say WHERE the data is rather than WHICH connection reaches it. The
     * resolver's whole safety case rests on the bucket coming from the connection record.
     */
    private static final List<String> LOCATION_AND_CREDENTIAL_WORDS = Arrays.asList(
        "bucket", "endpoint", "region", "url", "host", "port", "secret", "accesskey", "password",
        "credential", "connectionstring");

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void storesTheConnectionAliasAndNothingThatSaysWhereThatConnectionPoints() {
        List<String> offenders = new ArrayList<>();
        for (Field field : AnalyticsDataset.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            for (String word : LOCATION_AND_CREDENTIAL_WORDS) {
                if (name.contains(word)) {
                    offenders.add(field.getName());
                }
            }
        }
        assertThat(offenders)
            .as("a dataset stores the connection alias and the path; the bucket is read from the "
                + "connection at resolve time, and a copy here would be a second source of truth "
                + "for it that a repointed connection would silently leave stale")
            .isEmpty();
        assertThat(fieldNames()).contains("connectionAlias", "datasetPath");
    }

    @Test
    void belongsToExactlyOneWorkspace() {
        Filter filter = AnalyticsDataset.class.getAnnotation(Filter.class);
        assertThat(filter).isNotNull();
        assertThat(filter.name()).isEqualTo("tenantFilter");
        // Deliberately not StorageConnection's "(tenant_id = :tenantId or tenant_id is null)".
        // That form publishes the platform's rows to every tenant, which is right for a catalogue
        // the whole application resolves through and wrong for one person's saved work.
        assertThat(filter.condition()).isEqualTo("tenant_id = :tenantId");
    }

    @Test
    void isStampedWithItsAuthorWithoutTheSavingCodeRememberingTo() {
        EntityListeners listeners = AnalyticsDataset.class.getAnnotation(EntityListeners.class);
        assertThat(listeners).isNotNull();
        assertThat(listeners.value()).contains(AuditListener.class);

        AnalyticsDataset dataset = new AnalyticsDataset();
        TenantContext.set(1001L, "TENANT_USER", 55L, "user@acme.test");
        new AuditListener().onCreate(dataset);

        assertThat(dataset.getCreatedBy()).isEqualTo(55L);
        assertThat(dataset.getUpdatedBy()).isNull();

        TenantContext.set(1001L, "TENANT_ADMIN", 77L, "admin@acme.test");
        new AuditListener().onUpdate(dataset);

        assertThat(dataset.getCreatedBy()).isEqualTo(55L);
        assertThat(dataset.getUpdatedBy()).isEqualTo(77L);
    }

    /**
     * stage and prod run Hibernate with ddl-auto=validate, so a column the changeset does not
     * create is a startup failure there and nothing at all in dev, where ddl-auto=update quietly
     * adds it. This is the cheap version of that check.
     */
    @Test
    void everyMappedColumnIsCreatedByTheChangeset() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT);

        assertThat(sql).contains(AnalyticsDataset.class.getAnnotation(Table.class).name());
        // The id generator names a sequence Liquibase has to have created: dev's ddl-auto=update
        // would make one at 1, prod's validate would not.
        SequenceGenerator generator = AnalyticsDataset.class
            .getDeclaredField("analyticsDatasetId").getAnnotation(SequenceGenerator.class);
        assertThat(generator).isNotNull();
        assertThat(sql).contains(generator.sequenceName());

        List<String> missing = new ArrayList<>();
        for (Field field : AnalyticsDataset.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null || field.getAnnotation(Transient.class) != null) {
                continue;
            }
            if (!sql.contains(column.name().toLowerCase(Locale.ROOT))) {
                missing.add(column.name());
            }
        }
        assertThat(missing)
            .as("mapped by the entity but declared in no changeset the master runs -- a column "
                + "no database gets")
            .isEmpty();
    }

    private List<String> fieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : AnalyticsDataset.class.getDeclaredFields()) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Every changeset the master actually runs, concatenated -- not the one file that first
     * created this table.
     *
     * It read V31 alone until the V50 baseline replaced V1-V49 with a single declaration of the
     * schema, at which point a check pinned to V31 was reading a file no database receives. The
     * same weakness was already found once on the library side, where a guard pinned to V32
     * called V35's new columns unmigrated while they were migrated correctly.
     *
     * archive/ is skipped deliberately: it holds the superseded changesets, and SQL found only
     * there is SQL no database gets.
     */
    private String changesetSql() throws Exception {
        // Surefire runs from the module root; the second path is for a run from the parent.
        for (String prefix : new String[] { "", "process/" }) {
            File root = new File(prefix + "src/main/resources/db/changelog/changelog-sets");
            if (root.isDirectory()) {
                StringBuilder all = new StringBuilder();
                collectSql(root, all);
                return all.toString();
            }
        }
        throw new IllegalStateException("Could not find the changelog-sets directory from "
            + System.getProperty("user.dir"));
    }

    private static void collectSql(File directory, StringBuilder into) throws Exception {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (!"archive".equals(child.getName())) {
                    collectSql(child, into);
                }
            } else if (child.getName().endsWith(".sql")) {
                into.append(new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8))
                    .append('\n');
            }
        }
    }

}
