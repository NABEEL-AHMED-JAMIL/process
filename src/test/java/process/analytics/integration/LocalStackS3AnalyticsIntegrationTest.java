package process.analytics.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.analytics.dto.QueryResultDto;
import process.model.dto.ObjectContentDto;
import process.model.enums.UserRole;
import process.security.TenantContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.AGREEMENT_QUERY;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.FIXTURE_RELATION;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.S3_OTHER_BUCKET;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.S3_OTHER_KEY;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.TENANT_ID;

/**
 * The same engine reading the same two formats out of an S3 endpoint, through the S3 provider.
 *
 * MinIO and S3 share a branch in {@code DuckDbSessionFactory}, and it would be easy to call this
 * suite a duplicate of the MinIO one because of that. It is not, for three reasons that are all
 * about what is DIFFERENT once the provider enum changes:
 *
 * <ul>
 *   <li>The connection carries a REGION, which a MinIO connection here does not, so the
 *       {@code REGION} clause of the CREATE SECRET is executed for the first time.</li>
 *   <li>The storage adapter underneath is the AWS SDK's rather than MinIO's client, so the
 *       fixtures travel out through {@code S3ObjectStorageServiceImpl}.</li>
 *   <li>It is a genuinely different server. MinIO and LocalStack disagree about plenty at the
 *       edges of the S3 protocol, and an httpfs read that works against one is not evidence
 *       about the other.</li>
 * </ul>
 *
 * <b>What this suite does not prove, said plainly because the module has already been burned by
 * the tidier version of the sentence:</b> LocalStack is not AWS. Every S3 connection here carries
 * an explicit endpoint, so the branch in {@code s3Secret} that handles a BLANK endpoint -- real
 * AWS, virtual-host addressing, a region that actually resolves -- is still unexecuted by
 * anything. {@code DatasetResolver}'s Azure refusal used to end "S3 and MinIO have been
 * verified"; that clause was removed for exactly this reason and this file does not restore it.
 *
 * Unlike the MinIO suite, this one writes its own fixtures: LocalStack starts empty. The CSV and
 * the Parquet are written from one SELECT, so a later disagreement between them cannot be blamed
 * on the two files having been different all along.
 *
 * @author Nabeel Ahmed
 */
class LocalStackS3AnalyticsIntegrationTest {

    /** Written once for the class. Uploading five thousand rows per test would be waste, not rigour. */
    private static String csvKey;
    private static String parquetKey;
    private static File workspace;

    private AnalyticsQueryService service;
    private DatasetResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        AnalyticsIntegrationEnvironment.requireLocalStackS3();
        if (csvKey == null) {
            workspace = Files.createTempDirectory("analytics-s3-it").toFile();
            String[] keys = AnalyticsIntegrationEnvironment.writeCsvAndParquetFixture(
                AnalyticsIntegrationEnvironment.s3Connection(), "fixtures", FIXTURE_RELATION,
                workspace);
            csvKey = keys[0];
            parquetKey = keys[1];
        }
        this.service = AnalyticsIntegrationEnvironment.service();
        this.resolver = AnalyticsIntegrationEnvironment.resolverOver(
            AnalyticsIntegrationEnvironment.s3Connection());
        TenantContext.set(TENANT_ID, UserRole.TENANT_USER.name(), 5001L, "analytics-it@example.test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.service != null) {
            this.service.shutdown();
        }
    }

    @AfterAll
    static void removeLocalCopies() {
        // Only the temporary files this JVM wrote on its way to the upload. The objects in the
        // bucket are left where they are: they are cheap, they make a failed run reproducible,
        // and a suite that tidies up after itself is a suite that deletes the evidence.
        if (workspace != null) {
            File[] files = workspace.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            workspace.delete();
        }
    }

    /** The headline claim again, on a different server and through the other storage adapter. */
    @Test
    void csvAndParquetAnswerTheSameQuestionIdentically() throws Exception {
        QueryResultDto fromCsv = this.query(csvKey, AGREEMENT_QUERY);
        QueryResultDto fromParquet = this.query(parquetKey, AGREEMENT_QUERY);

        assertThat(fromCsv.getRows()).isNotEmpty();
        assertThat(fromCsv.getColumns()).isEqualTo(fromParquet.getColumns());
        assertThat(fromParquet.getRows()).isEqualTo(fromCsv.getRows());
        assertThat(fromCsv.getRows().size()).isGreaterThan(1);
    }

    /** The rows really crossed the network: the count matches what the fixture was written with. */
    @Test
    void bothFormatsHoldEveryRowTheFixtureWasWrittenWith() throws Exception {
        assertThat(this.service.rowCount(this.dataset(csvKey))).isEqualTo(5000L);
        assertThat(this.service.rowCount(this.dataset(parquetKey))).isEqualTo(5000L);
    }

    /**
     * A key that is not in the bucket is a refusal with a sentence, from a real 404.
     *
     * LocalStack answers a missing key differently from MinIO -- which is the point of running
     * this against both. The requirement is the same either way: a sentence, and no credential
     * in it.
     */
    @Test
    void aKeyThatIsNotThereIsRefusedWithoutLeakingTheCredential() throws Exception {
        final DatasetRef missing = this.dataset("fixtures/no-such-object.parquet");

        assertThatThrownBy(() -> this.service.rowCount(missing))
            .isInstanceOf(AnalyticsException.class)
            .satisfies(refusal -> {
                assertThat(refusal.getMessage()).isNotEmpty();
                assertThat(refusal.getMessage())
                    .doesNotContain(AnalyticsIntegrationEnvironment.S3_SECRET_KEY);
            });
    }

    /**
     * The bucket the connection does not own, with the control that makes the refusal mean
     * something.
     *
     * The session holds one connection's credentials, and DuckDB will spend them on any s3:// URL
     * a statement names. The first assertion proves those credentials genuinely reach the other
     * bucket -- the object is read back through the production adapter, byte for byte -- so when
     * the analytics API refuses the same object, the refusal is the only thing in the way. That
     * is the difference between testing a rule and testing an accident.
     *
     * Both shapes a location can arrive in are checked, because the gate checks table_name,
     * schema_name AND catalog_name for exactly this reason: a URL written as a quoted identifier
     * is folded back into the same replacement scan as one written as a table function.
     */
    @Test
    void aBucketThisConnectionDoesNotOwnCannotBeNamedInSql() throws Exception {
        ObjectContentDto readable = AnalyticsIntegrationEnvironment
            .storageFor(AnalyticsIntegrationEnvironment.s3ConnectionToOtherBucket())
            .getObjectContent(S3_OTHER_BUCKET, S3_OTHER_KEY, null, null);
        assertThat(contentOf(readable))
            .as("the control: these credentials must really be able to read the other bucket, "
                + "byte for byte, or the refusals below prove nothing")
            .isEqualTo(AnalyticsIntegrationEnvironment.S3_OTHER_CONTENT);

        final DatasetRef ours = this.dataset(csvKey);
        final String url = "s3://" + S3_OTHER_BUCKET + "/" + S3_OTHER_KEY;

        assertThatThrownBy(() ->
            this.service.query(ours, null, "SELECT * FROM read_csv_auto('" + url + "')"))
            .as("a table function is how a statement names a location nobody checked")
            .isInstanceOf(AnalyticsException.class);

        assertThatThrownBy(() ->
            this.service.query(ours, null, "SELECT * FROM \"" + url + "\""))
            .as("the same location as a quoted identifier reaches the same replacement scan")
            .isInstanceOf(AnalyticsException.class);
    }

    /**
     * A dataset the caller was never given cannot be reached by naming its connection.
     *
     * The resolver here knows only the S3 connection, so the MinIO alias is absent rather than
     * forbidden -- and the answer is the same sentence either way, which is the property that
     * stops an alias from being guessable.
     */
    @Test
    void anAliasThisWorkspaceWasNotGivenIsNotFound() {
        assertThatThrownBy(() -> this.resolver.resolve("analytics-it-minio", csvKey))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private DatasetRef dataset(String path) throws AnalyticsException {
        return this.resolver.resolve("analytics-it-s3", path);
    }

    private QueryResultDto query(String path, String sql) throws AnalyticsException {
        return this.service.query(this.dataset(path), null, sql);
    }

    private static String contentOf(ObjectContentDto object) throws Exception {
        InputStream stream = object.getContent();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }
}
