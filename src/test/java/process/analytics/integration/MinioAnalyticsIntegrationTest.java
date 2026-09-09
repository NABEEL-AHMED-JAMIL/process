package process.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;
import process.model.enums.UserRole;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.AGREEMENT_QUERY;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.BENCHMARK_CSV;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.BENCHMARK_PARQUET;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.MINIO_SECRET_KEY;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.OTHER_TENANT_ID;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.TENANT_ID;

/**
 * The analytics engine reading real objects out of a real MinIO.
 *
 * Nothing is substituted. The session is {@code DuckDbSessionFactory}'s -- httpfs installed and
 * loaded, a CREATE SECRET carrying the decrypted key, the local filesystem removed and the
 * configuration locked -- the dataset comes out of the real {@code DatasetResolver}, and the
 * bytes come over HTTP from the container on :9000. The unit suites next door swap the scan
 * expression for a VALUES list, which is right for them and is exactly why spec 13's integration
 * section was met by nothing.
 *
 * <b>The test that earns this file is {@link #csvAndParquetAnswerTheSameQuestionIdentically}.</b>
 * PROGRESS records Parquet at roughly 7.8x CSV on the 100 MB tier and the product tells users so
 * in as many words -- "Try a narrower dataset, or Parquet instead of CSV". A speed ratio between
 * two readers is only advice if the two readers agree about the data; if they do not, the fast
 * one is fast at being wrong. Until this test there was no automated check that they agree at
 * all, on any dataset, in either direction.
 *
 * The refusals matter as much as the reads. A dataset that is not there and a connection owned by
 * another workspace both have to produce a sentence somebody can act on, from a path that has
 * genuinely been out to the network and back -- a refusal that only ever happens against a stub
 * proves the stub refuses.
 *
 * See {@link AnalyticsIntegrationEnvironment} for what happens when MinIO is not running: these
 * tests skip with a reason, and {@code -Danalytics.it.required=true} turns that skip into a
 * failure for a build that provisions the stack.
 *
 * @author Nabeel Ahmed
 */
class MinioAnalyticsIntegrationTest {

    private AnalyticsQueryService service;
    private DatasetResolver resolver;

    @BeforeEach
    void setUp() {
        AnalyticsIntegrationEnvironment.requireBenchmarkData();
        this.service = AnalyticsIntegrationEnvironment.service();
        this.resolver = AnalyticsIntegrationEnvironment.resolverOver(
            AnalyticsIntegrationEnvironment.minioConnection(),
            AnalyticsIntegrationEnvironment.minioConnectionOfAnotherTenant());
        TenantContext.set(TENANT_ID, UserRole.TENANT_USER.name(), 5001L, "analytics-it@example.test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.service != null) {
            this.service.shutdown();
        }
    }

    /**
     * The same question, put to the same 150,000 rows stored two ways, answered identically.
     *
     * Compared cell by cell rather than by row count, because "both returned four rows" is what
     * a broken reader also does.
     */
    @Test
    void csvAndParquetAnswerTheSameQuestionIdentically() throws Exception {
        QueryResultDto fromCsv = this.query(BENCHMARK_CSV, AGREEMENT_QUERY);
        QueryResultDto fromParquet = this.query(BENCHMARK_PARQUET, AGREEMENT_QUERY);

        assertThat(fromCsv.getRows())
            .as("the CSV must actually have been read -- an empty result would make every "
                + "comparison below vacuously true")
            .isNotEmpty();
        assertThat(fromCsv.getColumns()).isEqualTo(fromParquet.getColumns());
        assertThat(fromParquet.getRows())
            .as("CSV and Parquet must agree cell for cell; the benchmark's speed ratio means "
                + "nothing if they do not")
            .isEqualTo(fromCsv.getRows());

        // Not a tautology-check on the fixture: it proves the aggregate saw more than one group,
        // so an equality that holds because both sides collapsed to nothing cannot pass here.
        assertThat(fromCsv.getRows().size()).isGreaterThan(1);
    }

    /** The two files carry the same columns, in the same order, under the same names. */
    @Test
    void bothFormatsPresentTheSameColumnsInTheSameOrder() throws Exception {
        DatasetSchemaDto csv = this.service.schemaOf(this.dataset(BENCHMARK_CSV));
        DatasetSchemaDto parquet = this.service.schemaOf(this.dataset(BENCHMARK_PARQUET));

        assertThat(names(csv)).isNotEmpty();
        assertThat(names(parquet))
            .as("a column that appears in one format and not the other would silently change "
                + "what a saved analysis means when its dataset is converted")
            .isEqualTo(names(csv));
        assertThat(csv.getFormat()).isEqualTo("CSV");
        assertThat(parquet.getFormat()).isEqualTo("PARQUET");
    }

    /** Both readers count the same rows, which is the cheapest thing a conversion can get wrong. */
    @Test
    void bothFormatsHoldTheSameNumberOfRows() throws Exception {
        long csv = this.service.rowCount(this.dataset(BENCHMARK_CSV));
        long parquet = this.service.rowCount(this.dataset(BENCHMARK_PARQUET));

        assertThat(csv).isGreaterThan(0L);
        assertThat(parquet).isEqualTo(csv);
    }

    /** A page of rows really came out of the store, with the right shape and the right count. */
    @Test
    void aPreviewReturnsRealRowsFromTheObjectStore() throws Exception {
        DatasetPreviewDto preview = this.service.preview(this.dataset(BENCHMARK_CSV), 0, 25);

        assertThat(preview.getRows()).hasSize(25);
        assertThat(preview.getColumns()).contains("region", "amount");
        assertThat(preview.getTotalRows()).isGreaterThan(25L);
        for (List<String> row : preview.getRows()) {
            assertThat(row).hasSameSizeAs(preview.getColumns());
        }
    }

    /**
     * A key that is not there is a refusal with a sentence, not a stack trace and not a leak.
     *
     * The second assertion is the one that could not be made anywhere else. DuckDbSessionFactory
     * claims that attaching credentials with CREATE SECRET keeps them out of query plans and
     * error messages, unlike the older SET s3_access_key_id style. That claim is about what a
     * REAL engine does when a REAL remote read fails, so a unit test cannot make it and this is
     * the first place it has ever been checked.
     */
    @Test
    void aDatasetThatIsNotThereIsRefusedWithoutLeakingTheCredential() throws Exception {
        final DatasetRef missing = this.dataset("analytics-benchmark/there-is-no-such-file.csv");

        assertThatThrownBy(() -> this.service.rowCount(missing))
            .isInstanceOf(AnalyticsException.class)
            .satisfies(refusal -> {
                assertThat(refusal.getMessage()).isNotEmpty();
                assertThat(refusal.getMessage())
                    .as("the secret key must never reach a message on its way to a user")
                    .doesNotContain(MINIO_SECRET_KEY);
            });
    }

    /**
     * The same store, recorded as another workspace's connection, is not found.
     *
     * Not "forbidden": the resolver answers absent and forbidden with one sentence on purpose, so
     * an alias cannot be walked to learn what other workspaces own.
     *
     * The two connections here differ in one field. Same endpoint, same bucket, same credentials,
     * same objects -- only the recorded owner. So the control below is doing real work: it proves
     * this resolver resolves, against a store that is genuinely reachable, which is what makes
     * the two refusals statements about tenancy rather than about a broken endpoint. Both
     * directions are asserted because a rule that only holds one way round is not a boundary.
     */
    @Test
    void aConnectionOwnedByAnotherWorkspaceIsNotFound() throws Exception {
        assertThat(this.resolver.resolve("analytics-it-minio", BENCHMARK_CSV))
            .as("the control: this workspace's own connection must resolve")
            .isNotNull();

        assertThatThrownBy(() -> this.resolver.resolve("analytics-it-foreign", BENCHMARK_CSV))
            .as("a connection belonging to another workspace")
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");

        TenantContext.set(OTHER_TENANT_ID, UserRole.TENANT_USER.name(), 5002L, "other@example.test");
        assertThatThrownBy(() -> this.resolver.resolve("analytics-it-minio", BENCHMARK_CSV))
            .as("and the same refusal seen from the other side of the boundary")
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private DatasetRef dataset(String path) throws AnalyticsException {
        return this.resolver.resolve("analytics-it-minio", path);
    }

    private QueryResultDto query(String path, String sql) throws AnalyticsException {
        return this.service.query(this.dataset(path), null, sql);
    }

    private static List<String> names(DatasetSchemaDto schema) {
        List<String> names = new ArrayList<String>();
        for (ColumnDto column : schema.getColumns()) {
            names.add(column.getName());
        }
        return names;
    }
}
