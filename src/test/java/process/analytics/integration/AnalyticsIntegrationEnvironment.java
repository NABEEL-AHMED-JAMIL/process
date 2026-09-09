package process.analytics.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.AnalyticsLimits;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetResolver;
import process.analytics.DuckDbSessionFactory;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.util.EncryptionUtil;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The infrastructure the analytics integration suites need, and the single decision about what
 * happens when it is not there.
 *
 * <b>Why these suites exist.</b> Every analytics test written before them substitutes the
 * LOCATION: the scan expression is swapped for a literal relation, or for a file on the local
 * disk. That substitution is honest in a unit test -- there is no object store in a unit test --
 * and it is exactly why spec 13's "Integration: S3, MinIO, Azure Blob, DuckDB, PostgreSQL,
 * Redis, Kafka" was met by nothing at all. A stub cannot fail the way httpfs fails on a 404, an
 * S3 secret cannot be wrong in a stub, and "CSV and Parquet return the same answer" -- the claim
 * the whole benchmark rests on -- is unfalsifiable when both sides are the same VALUES list.
 * These suites read real bytes out of a real store through the real
 * {@link DuckDbSessionFactory} session, with httpfs loaded and a real CREATE SECRET attached.
 *
 * <h3>Which of spec 13's seven integration targets this package covers</h3>
 *
 * <ul>
 *   <li><b>MinIO</b> -- covered, against the running container and the benchmark data the
 *       published numbers were measured on.</li>
 *   <li><b>S3</b> -- covered against LocalStack, which is an S3-compatible endpoint and not AWS.
 *       The blank-endpoint branch of {@code s3Secret} -- real AWS, virtual-host addressing --
 *       is still unexercised, and this package does not claim otherwise.</li>
 *   <li><b>DuckDB</b> -- covered throughout: every read here runs on a real embedded engine.</li>
 *   <li><b>PostgreSQL</b> -- covered as the schema the entities map, which is what would stop
 *       the application starting.</li>
 *   <li><b>Azure Blob</b> -- NOT covered, and not coverable here: there is no Azurite or other
 *       emulator on this machine, and {@code DatasetResolver} refuses AZURE outright as
 *       unverified. Writing a "test" against the refusal and calling that Azure integration
 *       would be precisely the overclaim the refusal exists to prevent.</li>
 *   <li><b>Redis</b> -- spec 13 says "Redis if used", and analytics does not use it. Nothing to
 *       integrate against.</li>
 *   <li><b>Kafka</b> -- spec 13 says "where applicable", and no analytics path produces or
 *       consumes a message. The broker on this machine belongs to the ETL half of the
 *       application, which has its own suite.</li>
 * </ul>
 *
 * <h3>How a missing stack is handled, and why it is an assumption rather than a profile</h3>
 *
 * These tests need MinIO, LocalStack and PostgreSQL. A colleague's laptop and a CI runner have
 * none of that, and the suite has to be worth keeping on both kinds of machine.
 *
 * <b>Rejected: a Maven profile or a JUnit @Tag.</b> Both make the suite opt-IN, and this repo
 * already has the evidence for where that ends. There are eight {@code *IT.java} classes in
 * src/test today -- the Kafka security matrix, the bucket-access end-to-end, the OpenSearch RAG
 * client -- and Surefire's default includes are {@code Test*.java}, {@code *Test.java},
 * {@code *Tests.java}, {@code *TestCase.java}. {@code *IT.java} matches none of them, this pom
 * configures neither Surefire includes nor Failsafe, and the house build command is
 * {@code mvn -o package}, which stops before {@code verify} anyway. So not one of those classes
 * has ever run in the build: {@code target/surefire-reports} has a report for every
 * {@code *Test} and for none of the {@code *IT}. Nobody deleted that suite in frustration; it
 * just quietly stopped being executed, which is the same outcome reached more politely. These
 * classes are therefore named {@code *Test}, so the ordinary build runs them.
 *
 * <b>Chosen: a JUnit assumption per suite, from one cached probe.</b> A developer with the stack
 * up gets real coverage from {@code mvn -o package} with no flag to remember. A developer
 * without it gets SKIPPED and a sentence naming the endpoint that was tried and what to start.
 * The probe costs one connection attempt per dependency per JVM, with a short timeout, so the
 * cost of the skip is a second at most.
 *
 * <b>And the escape hatch that makes the skip trustworthy: {@code -Danalytics.it.required=true}
 * turns every absence into a failure.</b> Without it, "green" and "skipped everything" look
 * identical from outside, and a suite that can silently contribute nothing is a suite that
 * eventually does. A CI job that provisions the stack should set that flag; then a broken MinIO
 * fails the build instead of quietly halving the coverage.
 *
 * <h3>Credentials</h3>
 *
 * Every endpoint and credential is read from a system property first, then from the environment
 * variable the application itself uses, and only then falls back to the value this repository's
 * own {@code docker-compose.yml} already commits as the local default. Nothing here is a secret
 * that was not already checked in beside the compose file that creates it, and a developer whose
 * stack differs overrides one property rather than editing a test.
 *
 * @author Nabeel Ahmed
 */
final class AnalyticsIntegrationEnvironment {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsIntegrationEnvironment.class);

    /** Turns "infrastructure absent" from a skip into a failure. See the class comment. */
    private static final boolean REQUIRED = Boolean.getBoolean("analytics.it.required");

    // ---- MinIO ---------------------------------------------------------------------------

    static final String MINIO_ENDPOINT =
        setting("analytics.it.minio.endpoint", "MINIO_ENDPOINT", "http://localhost:9000");
    static final String MINIO_ACCESS_KEY =
        setting("analytics.it.minio.access-key", "MINIO_ACCESS_KEY", "minioadmin");
    static final String MINIO_SECRET_KEY =
        setting("analytics.it.minio.secret-key", "MINIO_SECRET_KEY", "minioadmin123");
    static final String MINIO_BUCKET =
        setting("analytics.it.minio.bucket", "ANALYTICS_IT_MINIO_BUCKET", "etl-bucket");

    /**
     * The benchmark pair these suites read, and the reason they read data they did not create.
     *
     * PROGRESS records 150,000 rows written as CSV and as Parquet from the same generator, with
     * a row shape deliberately unflattering to Parquet. Regenerating that inside a test would be
     * a different file measured by a different tool; reading the one the benchmark numbers were
     * taken from is what makes "the two formats agree" a statement about the benchmark rather
     * than about a fixture.
     */
    static final String BENCHMARK_CSV = "analytics-benchmark/sales-10mb.csv";
    static final String BENCHMARK_PARQUET = "analytics-benchmark/sales-10mb.parquet";

    // ---- LocalStack ----------------------------------------------------------------------

    static final String S3_ENDPOINT =
        setting("analytics.it.s3.endpoint", "ANALYTICS_IT_S3_ENDPOINT", "http://localhost:4566");
    static final String S3_REGION =
        setting("analytics.it.s3.region", "AWS_S3_REGION", "us-east-1");
    static final String S3_ACCESS_KEY =
        setting("analytics.it.s3.access-key", "ANALYTICS_IT_S3_ACCESS_KEY", "test");
    static final String S3_SECRET_KEY =
        setting("analytics.it.s3.secret-key", "ANALYTICS_IT_S3_SECRET_KEY", "test");

    /**
     * A bucket of this suite's own, created on first use.
     *
     * LocalStack starts empty and keeps no state across restarts here (persistence is disabled),
     * so unlike MinIO there is nothing to read that the suite did not put there. Writing its own
     * fixtures is not a weaker test -- it is what lets the CSV and the Parquet be generated from
     * one relation, so a disagreement between them cannot be blamed on the two files differing.
     */
    static final String S3_BUCKET =
        setting("analytics.it.s3.bucket", "ANALYTICS_IT_S3_BUCKET", "analytics-it");

    /**
     * A second bucket the same credentials can read and no connection record points at.
     *
     * It exists so "a bucket the connection does not own" can be tested as the real thing rather
     * than as a name that happens not to resolve. The key below is genuinely readable with the
     * suite's own credentials -- the control asserts that -- so when the analytics API refuses
     * it, the refusal is the only thing standing in the way.
     */
    static final String S3_OTHER_BUCKET =
        setting("analytics.it.s3.other-bucket", "ANALYTICS_IT_S3_OTHER_BUCKET", "analytics-it-other");

    static final String S3_OTHER_KEY = "not-yours/secret.csv";

    static final String S3_OTHER_CONTENT = "secret\nthis belongs to another connection\n";

    // ---- the query both object-store suites put to both formats -----------------------------

    /**
     * One aggregate over every column shape the fixtures carry: a low-cardinality dimension, a
     * high-cardinality one, a decimal and a date.
     *
     * Shared by the MinIO and the S3 suite because the claim is the same in both and a second
     * copy of it would be a second thing to keep in step.
     *
     * The two casts are not decoration. CSV infers its types from the text and Parquet carries
     * its own, so {@code sum(amount)} can be a DOUBLE on one side and a DECIMAL on the other, and
     * the value crosses this API as a String -- {@code 7.466125E7} against {@code 74661250.00}
     * would be a difference in RENDERING dressed up as a difference in data. Casting both sides
     * to one type asks the question these suites mean to ask, which is whether the two readers
     * agree about the values.
     */
    static final String AGREEMENT_QUERY =
        "SELECT region, "
        + "count(*) AS orders, "
        + "count(DISTINCT customer) AS customers, "
        + "CAST(sum(amount) AS DECIMAL(18,2)) AS total, "
        + "CAST(min(booked_on) AS DATE) AS first_booked "
        + "FROM dataset GROUP BY region ORDER BY region";

    /**
     * The rows both generated fixtures are written from.
     *
     * Deliberately the same column shape as the benchmark data in MinIO -- a dimension with four
     * values, a customer with thousands, a decimal, a date and free text -- so the query above is
     * one query and not two that look alike. Small, because this fixture is uploaded on every
     * run and its job is to prove the path works, not to measure it.
     */
    static final String FIXTURE_RELATION =
        "SELECT i AS id, "
        + "(['north','south','east','west'])[(i % 4) + 1] AS region, "
        + "'cust-' || ((i * 7919) % 5000) AS customer, "
        + "CAST((i % 977) / 100.0 AS DECIMAL(12,2)) AS amount, "
        // to_days rather than "+ (i % 365)": DuckDB 1.1.3 has no DATE + INTEGER operator, and the
        // cast back to DATE is because DATE + INTERVAL widens to TIMESTAMP.
        + "CAST(DATE '2024-01-01' + to_days(CAST(i % 365 AS INTEGER)) AS DATE) AS booked_on, "
        + "'order note ' || (i % 31) AS note "
        + "FROM range(0, 5000) AS t(i)";

    // ---- PostgreSQL ----------------------------------------------------------------------

    static final String POSTGRES_URL = setting("analytics.it.postgres.url",
        "ANALYTICS_IT_POSTGRES_URL", "jdbc:postgresql://localhost:5433/etl_job");
    static final String POSTGRES_USERNAME = setting("analytics.it.postgres.username",
        "SPRING_DATASOURCE_USERNAME", "nabeel.amd93");
    static final String POSTGRES_PASSWORD = setting("analytics.it.postgres.password",
        "SPRING_DATASOURCE_PASSWORD", "admin");

    // ---- tenancy ---------------------------------------------------------------------------

    /** The workspace these suites act as. Arbitrary; nothing is written to the database. */
    static final long TENANT_ID = 90001L;

    /** A second workspace, which owns nothing here and must therefore reach nothing. */
    static final long OTHER_TENANT_ID = 90002L;

    /**
     * One key for the whole JVM, generated rather than written down.
     *
     * A storage secret is encrypted by these suites and decrypted by DuckDbSessionFactory inside
     * the same process, so the key has to round-trip and does not have to survive the run. A
     * constant in the source would be a credential-shaped string in the repository for no gain.
     */
    private static final EncryptionUtil ENCRYPTION = encryptionUtil();

    private static Boolean minioAvailable;
    private static String minioUnavailableBecause;
    private static Boolean benchmarkDataPresent;
    private static String benchmarkMissingBecause;
    private static Boolean s3Available;
    private static String s3UnavailableBecause;
    private static Boolean postgresAvailable;
    private static String postgresUnavailableBecause;

    private AnalyticsIntegrationEnvironment() {}

    // ---- availability ----------------------------------------------------------------------

    /**
     * Aborts (or fails, under {@code -Danalytics.it.required=true}) unless MinIO answers.
     *
     * The probe is a real listObjects through the production adapter rather than a health ping,
     * because a MinIO that is up and rejecting these credentials is, for this suite, exactly as
     * unusable as one that is down -- and a health ping would call it available and leave every
     * test failing on authorization instead of skipping with a reason.
     */
    static void requireMinio() {
        if (minioAvailable == null) {
            try {
                listening(MINIO_ENDPOINT);
                storageFor(minioConnection()).listObjects(MINIO_BUCKET, "", null, 1);
                minioAvailable = Boolean.TRUE;
            } catch (Throwable failure) {
                minioAvailable = Boolean.FALSE;
                minioUnavailableBecause = shortReason(failure);
            }
            logger.info("Analytics integration: MinIO at {} is {}", MINIO_ENDPOINT,
                Boolean.TRUE.equals(minioAvailable) ? "available" : "unavailable -- " + minioUnavailableBecause);
        }
        available(minioAvailable, "MinIO at " + MINIO_ENDPOINT + " (bucket " + MINIO_BUCKET + ")",
            minioUnavailableBecause,
            "Start the local stack (docker-compose up minio), or point the suite elsewhere with "
                + "-Danalytics.it.minio.endpoint=");
    }

    /** The benchmark pair, checked separately: MinIO can be up and the objects still absent. */
    static void requireBenchmarkData() {
        requireMinio();
        if (benchmarkDataPresent == null) {
            try {
                ObjectStorageService storage = storageFor(minioConnection());
                storage.getObjectMetadata(MINIO_BUCKET, BENCHMARK_CSV);
                storage.getObjectMetadata(MINIO_BUCKET, BENCHMARK_PARQUET);
                benchmarkDataPresent = Boolean.TRUE;
            } catch (Throwable failure) {
                benchmarkDataPresent = Boolean.FALSE;
                benchmarkMissingBecause = shortReason(failure);
            }
        }
        available(benchmarkDataPresent,
            "the benchmark pair " + MINIO_BUCKET + "/" + BENCHMARK_CSV + " and "
                + MINIO_BUCKET + "/" + BENCHMARK_PARQUET,
            benchmarkMissingBecause,
            "Upload the 10 MB tier described in .ai/spec/PROGRESS.md, or point the suite at a "
                + "store that has it.");
    }

    /** Aborts unless LocalStack answers, creating this suite's own bucket on the first call. */
    static void requireLocalStackS3() {
        if (s3Available == null) {
            try {
                listening(S3_ENDPOINT);
                createBucketIfAbsent(S3_BUCKET);
                createBucketIfAbsent(S3_OTHER_BUCKET);
                storageFor(s3Connection()).listObjects(S3_BUCKET, "", null, 1);
                putText(s3ConnectionToOtherBucket(), S3_OTHER_KEY, S3_OTHER_CONTENT);
                s3Available = Boolean.TRUE;
            } catch (Throwable failure) {
                s3Available = Boolean.FALSE;
                s3UnavailableBecause = shortReason(failure);
            }
            logger.info("Analytics integration: S3 at {} is {}", S3_ENDPOINT,
                Boolean.TRUE.equals(s3Available) ? "available" : "unavailable -- " + s3UnavailableBecause);
        }
        available(s3Available, "an S3 endpoint at " + S3_ENDPOINT,
            s3UnavailableBecause,
            "Start LocalStack, or point the suite at another S3 with -Danalytics.it.s3.endpoint=, "
                + "-Danalytics.it.s3.access-key= and -Danalytics.it.s3.secret-key=");
    }

    /** Aborts unless the analytics database answers. */
    static void requirePostgres() {
        if (postgresAvailable == null) {
            // Bounded and then put back: setLoginTimeout is global to the JVM, and DuckDB reaches
            // for DriverManager in every other suite in this package.
            int previousLoginTimeout = DriverManager.getLoginTimeout();
            DriverManager.setLoginTimeout(3);
            Connection probe = null;
            try {
                probe = postgres();
                postgresAvailable = Boolean.TRUE;
            } catch (Throwable failure) {
                postgresAvailable = Boolean.FALSE;
                postgresUnavailableBecause = shortReason(failure);
            } finally {
                closeQuietly(probe);
                DriverManager.setLoginTimeout(previousLoginTimeout);
            }
            logger.info("Analytics integration: PostgreSQL at {} is {}", POSTGRES_URL,
                Boolean.TRUE.equals(postgresAvailable) ? "available" : "unavailable -- " + postgresUnavailableBecause);
        }
        available(postgresAvailable, "PostgreSQL at " + POSTGRES_URL,
            postgresUnavailableBecause,
            "Start the local stack (docker-compose up postgres), or supply "
                + "-Danalytics.it.postgres.url=, -Danalytics.it.postgres.username= and "
                + "-Danalytics.it.postgres.password=");
    }

    /**
     * One place decides skip-or-fail, so no suite can accidentally choose differently.
     *
     * The message is written for whoever reads the Surefire output six months from now and has
     * no idea what this suite wanted: it names the thing, what went wrong, and what to start.
     */
    private static void available(Boolean present, String what, String because, String how) {
        if (Boolean.TRUE.equals(present)) {
            return;
        }
        String message = what + " is not available (" + because + "). " + how;
        if (REQUIRED) {
            throw new AssertionError(message
                + " -- failing rather than skipping because -Danalytics.it.required=true was set.");
        }
        assumeTrue(false, message);
    }

    // ---- fixtures --------------------------------------------------------------------------

    /**
     * The production limits, with the numbers a test can wait for.
     *
     * Only the timeout and the row ceiling differ from the shipped defaults, and both are
     * lowered rather than raised: a suite that hangs for two minutes on a broken endpoint is a
     * suite people learn to skip. Every other field is the deployed value, because the memory
     * ceiling and the thread count are part of what is being exercised.
     */
    static AnalyticsLimits limits() {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "enabled", true);
        ReflectionTestUtils.setField(limits, "timeoutSeconds", 60);
        ReflectionTestUtils.setField(limits, "maxRows", 10000);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "profileSampleRows", 1000000L);
        ReflectionTestUtils.setField(limits, "benchmarkEnabled", true);
        ReflectionTestUtils.setField(limits, "parquetConversionEnabled", true);
        ReflectionTestUtils.setField(limits, "memoryLimit", "512MB");
        ReflectionTestUtils.setField(limits, "threads", 2);
        return limits;
    }

    static EncryptionUtil encryption() {
        return ENCRYPTION;
    }

    private static EncryptionUtil encryptionUtil() {
        EncryptionUtil util = new EncryptionUtil();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(util, "base64Key", Base64.getEncoder().encodeToString(key));
        return util;
    }

    /** The real factory, with the real lock-down. Nothing about it is relaxed for these suites. */
    static DuckDbSessionFactory sessions() {
        return new DuckDbSessionFactory(limits(), ENCRYPTION);
    }

    static AnalyticsQueryService service() {
        return new AnalyticsQueryService(sessions(), limits());
    }

    /** A MinIO connection owned by {@link #TENANT_ID}, pointing at the local stack's bucket. */
    static StorageConnection minioConnection() {
        return connection(1L, StorageProvider.MINIO, "analytics-it-minio", MINIO_BUCKET,
            MINIO_ENDPOINT, null, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, TENANT_ID);
    }

    /** The same store, recorded as belonging to a workspace the caller is not in. */
    static StorageConnection minioConnectionOfAnotherTenant() {
        return connection(2L, StorageProvider.MINIO, "analytics-it-foreign", MINIO_BUCKET,
            MINIO_ENDPOINT, null, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, OTHER_TENANT_ID);
    }

    /** A connection recorded as S3 rather than MinIO, pointed at LocalStack. */
    static StorageConnection s3Connection() {
        return connection(3L, StorageProvider.S3, "analytics-it-s3", S3_BUCKET,
            S3_ENDPOINT, S3_REGION, S3_ACCESS_KEY, S3_SECRET_KEY, TENANT_ID);
    }

    /**
     * The same credentials bound to the OTHER bucket.
     *
     * Never handed to a resolver -- it exists so a test can prove, through the production
     * adapter, that these credentials really do reach that bucket. Without that control, a
     * refusal from the analytics API proves nothing: an unreadable bucket refuses itself.
     */
    static StorageConnection s3ConnectionToOtherBucket() {
        return connection(4L, StorageProvider.S3, "analytics-it-s3-other", S3_OTHER_BUCKET,
            S3_ENDPOINT, S3_REGION, S3_ACCESS_KEY, S3_SECRET_KEY, TENANT_ID);
    }

    private static StorageConnection connection(long id, StorageProvider provider, String alias,
        String bucket, String endpoint, String region, String accessKey, String secretKey,
        Long tenantId) {

        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setProvider(provider);
        connection.setAlias(alias);
        connection.setConnectionName(alias);
        connection.setBucketName(bucket);
        connection.setEndpoint(endpoint);
        connection.setRegion(region);
        connection.setAccessKey(accessKey);
        connection.setSecretKeyEnc(ENCRYPTION.encrypt(secretKey));
        connection.setStatus(Status.Active);
        connection.setTenantId(tenantId);
        return connection;
    }

    /**
     * A resolver over exactly these connections, and nothing else.
     *
     * The repository is stubbed and the resolver is not, which is the split that matters: the
     * tenant check, the Active check, the path allow-list and the format check are all the
     * production ones, so a refusal here is the refusal a request would get. Stubbing the
     * resolver instead would have made every tenancy assertion in these suites an assertion
     * about the stub.
     */
    static DatasetResolver resolverOver(StorageConnection... connections) {
        StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
        final Map<String, StorageConnection> byAlias = new HashMap<String, StorageConnection>();
        for (StorageConnection connection : connections) {
            byAlias.put(connection.getAlias(), connection);
        }
        when(repository.findByAlias(anyString())).thenAnswer(invocation -> {
            String alias = invocation.getArgument(0, String.class);
            return Optional.ofNullable(byAlias.get(alias == null ? null : alias.trim()));
        });
        return new DatasetResolver(repository);
    }

    /** The production adapter for this connection's provider -- MinIO's or the AWS SDK's. */
    static ObjectStorageService storageFor(StorageConnection connection) {
        return new StorageClientFactory(ENCRYPTION, null).buildUncached(connection);
    }

    static Connection postgres() throws Exception {
        return DriverManager.getConnection(POSTGRES_URL, POSTGRES_USERNAME, POSTGRES_PASSWORD);
    }

    // ---- fixture generation -----------------------------------------------------------------

    /**
     * Writes one relation out as CSV and as Parquet and uploads both, returning the two keys.
     *
     * <b>The generating session is a plain DuckDB connection, deliberately not one of
     * {@link DuckDbSessionFactory}'s.</b> A factory session has no local filesystem and a locked
     * configuration -- that is the property the rest of this package exists to prove -- so it
     * cannot write a file, and asking it to would be reaching for a hole rather than testing
     * one. This is fixture generation, on the way IN; every read the suites assert on still goes
     * through the locked-down session.
     *
     * Both files come from the same SELECT, so if the two formats disagree later it is the
     * READERS disagreeing and not two files that were never the same.
     */
    static String[] writeCsvAndParquetFixture(StorageConnection connection, String prefix,
        String relation, File directory) throws Exception {

        File csv = new File(directory, "fixture.csv");
        File parquet = new File(directory, "fixture.parquet");
        Connection duck = DriverManager.getConnection("jdbc:duckdb:");
        try {
            Statement statement = duck.createStatement();
            try {
                statement.execute("COPY (" + relation + ") TO '" + sqlLiteral(csv.getAbsolutePath())
                    + "' (FORMAT CSV, HEADER)");
                statement.execute("COPY (" + relation + ") TO '" + sqlLiteral(parquet.getAbsolutePath())
                    + "' (FORMAT PARQUET)");
            } finally {
                statement.close();
            }
        } finally {
            duck.close();
        }

        String csvKey = prefix + "/fixture.csv";
        String parquetKey = prefix + "/fixture.parquet";
        upload(connection, csvKey, csv, "text/csv");
        upload(connection, parquetKey, parquet, "application/octet-stream");
        return new String[] { csvKey, parquetKey };
    }

    private static void upload(StorageConnection connection, String key, File file,
        String contentType) throws Exception {

        InputStream stream = new FileInputStream(file);
        try {
            storageFor(connection).uploadObject(connection.getBucketName(), key, stream,
                file.length(), contentType);
        } finally {
            stream.close();
        }
    }

    /** Puts a small object there directly, for the cases that need one without a DuckDB reader. */
    static void putText(StorageConnection connection, String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageFor(connection).uploadObject(connection.getBucketName(), key,
            new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }

    /**
     * Creates this suite's LocalStack bucket, tolerating the case where it is already there.
     *
     * Not routed through ObjectStorageService because that interface has no bucket lifecycle --
     * deliberately, since the application only ever reads buckets somebody else created.
     */
    private static void createBucketIfAbsent(String bucket) {
        S3Client client = S3Client.builder()
            .region(Region.of(S3_REGION))
            .endpointOverride(URI.create(S3_ENDPOINT))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
            .build();
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
            // Every run after the first.
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException ignored) {
            // Same, on an endpoint that reports it the other way.
        } finally {
            client.close();
        }
    }

    // ---- small helpers ----------------------------------------------------------------------

    /** Single quotes doubled, so a temp path with an apostrophe cannot end the literal. */
    static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /** How long the suite waits to find out that nothing is listening. */
    private static final int TCP_PROBE_MILLIS = 1500;

    /**
     * A plain TCP connect before any SDK is touched, and the reason it is worth the eight lines.
     *
     * The MinIO client's default timeout is five minutes and the AWS SDK retries before it gives
     * up. On a laptop with nothing running, the port is closed and both answer instantly -- but
     * on a machine where the address is firewalled or black-holed, "skipped" would arrive several
     * minutes after the build appeared to hang, which is how a suite gets a reputation and then
     * gets deleted. This bounds the answer to a second and a half.
     */
    private static void listening(String endpoint) throws Exception {
        URI uri = URI.create(endpoint);
        int port = uri.getPort() > 0
            ? uri.getPort()
            : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(uri.getHost(), port), TCP_PROBE_MILLIS);
        } catch (Exception unreachable) {
            throw new IllegalStateException("nothing is listening on " + uri.getHost() + ":" + port);
        } finally {
            socket.close();
        }
    }

    private static String setting(String property, String environmentVariable, String fallback) {
        String value = System.getProperty(property);
        if (isBlank(value)) {
            value = System.getenv(environmentVariable);
        }
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * The first line of why something is unreachable, without the stack.
     *
     * A skip message is read in a wall of Surefire output; a hundred frames of AWS SDK retry
     * there would bury the one sentence that says what to start.
     */
    private static String shortReason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return root.getClass().getSimpleName();
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // The probe's result is already recorded; a failure to close adds nothing.
        }
    }
}
