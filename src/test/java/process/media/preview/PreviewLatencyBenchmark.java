package process.media.preview;

import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * MIG-36's numbers: what a table/archive preview costs, split into authorisation, transfer and
 * parse, and what each byte-transfer model would add if preview left the process that owns
 * storage access.
 *
 * Not a test: named *Benchmark so surefire never runs it. Run it deliberately, with LocalStack up:
 *   mvn -o test -Dtest=PreviewLatencyBenchmark
 * It writes to a scratch bucket, mig36-bench, and prints a table.
 */
class PreviewLatencyBenchmark {

    private static final String ENDPOINT = "http://localhost:4566";
    private static final String BUCKET = "mig36-bench";
    private static final int RUNS = 5;

    private static S3Client s3;
    private static S3Presigner presigner;
    private static final Map<String, Long> SIZES = new LinkedHashMap<>();

    @BeforeAll
    static void files() throws Exception {
        boolean up;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT + "/_localstack/health").openConnection();
            c.setConnectTimeout(1000);
            up = c.getResponseCode() == 200;
        } catch (Exception e) {
            up = false;
        }
        assumeTrue(up, "LocalStack is not running");
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        s3 = S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create(ENDPOINT)).forcePathStyle(true)
            .credentialsProvider(creds).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(URI.create(ENDPOINT))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).credentialsProvider(creds).build();
        try {
            s3.createBucket(b -> b.bucket(BUCKET));
        } catch (Exception exists) {
            // already there from an earlier run
        }
        put("orders-200k.csv", csv(200_000), "text/csv");
        put("orders-1m.parquet", parquet(1_000_000), "application/octet-stream");
        put("orders-20k.xlsx", workbook(20_000), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        put("exports-1000.zip", zip(1000), "application/zip");
    }

    private static void put(String key, byte[] bytes, String type) {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).contentType(type).build(), RequestBody.fromBytes(bytes));
        SIZES.put(key, (long) bytes.length);
    }

    private static byte[] csv(int rows) {
        StringBuilder out = new StringBuilder("order_id,region,customer,sku,quantity,unit_price,status,created_at\n");
        for (int i = 0; i < rows; i++) {
            out.append(i).append(",north-").append(i % 7).append(",customer-").append(i % 5000).append(",SKU-")
                .append(i % 900).append(',').append(i % 40).append(',').append(i % 1000).append(".99,")
                .append(i % 3 == 0 ? "shipped" : "pending").append(",2026-09-").append(10 + i % 20).append("T10:00:00Z\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] parquet(int rows) throws Exception {
        Path file = Files.createTempFile("bench-", ".parquet");
        Files.delete(file);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = duck.createStatement()) {
            sql.execute("copy (select i as order_id, 'north-' || (i % 7) as region, 'customer-' || (i % 5000) as customer, "
                + "i % 40 as quantity, (i % 1000) + 0.99 as unit_price from range(" + rows + ") t(i)) to '"
                + file.toAbsolutePath() + "' (format parquet)");
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    private static byte[] workbook(int rows) throws Exception {
        try (SXSSFWorkbook book = new SXSSFWorkbook(200)) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.createSheet("orders");
            for (int r = 0; r <= rows; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < 8; c++) {
                    if (r == 0) row.createCell(c).setCellValue("col" + c);
                    else row.createCell(c).setCellValue(c % 2 == 0 ? "v" + (r * c % 997) : String.valueOf(r * c));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            book.dispose();
            return out.toByteArray();
        }
    }

    private static byte[] zip(int entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] body = csv(200);
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new ZipEntry("exports/part-" + i + ".csv"));
                zip.write(body);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---- the three transfer models ---------------------------------------------------------------

    /** Today: the JVM that owns storage access reads the object itself. */
    private static byte[] inProcess(String key) throws Exception {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build())) {
            return drain(in);
        }
    }

    /** Presigned: Storage authorises and signs (HEAD + sign), Media reads the object from S3 directly. */
    private static byte[] presigned(String key) throws Exception {
        s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        URL url = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
            .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key(key).build()).build()).url();
        try (InputStream in = url.openStream()) {
            return drain(in);
        }
    }

    private static HttpServer relay;
    private static int relayPort;

    /** Proxied: Media asks Storage, Storage streams the object through itself (one more hop). */
    private static byte[] proxied(String key) throws Exception {
        if (relay == null) {
            relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            relay.createContext("/", exchange -> {
                String k = exchange.getRequestURI().getPath().substring(1);
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(k).build())) {
                    exchange.sendResponseHeaders(200, in.response().contentLength());
                    try (OutputStream out = exchange.getResponseBody()) {
                        byte[] chunk = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
                    }
                }
            });
            relay.start();
            relayPort = relay.getAddress().getPort();
        }
        try (InputStream in = new URL("http://127.0.0.1:" + relayPort + "/" + key).openStream()) {
            return drain(in);
        }
    }

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
        return out.toByteArray();
    }

    private static long medianMillis(Callable work) throws Exception {
        work.call();
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            work.call();
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(times);
        return times.get(RUNS / 2);
    }

    interface Callable {
        void call() throws Exception;
    }

    @Test
    void measure() throws Exception {
        ObjectPreviewServiceImpl preview = new ObjectPreviewServiceImpl(null, mock(process.media.extraction.ExtractionService.class));
        Map<String, Callable> parse = new LinkedHashMap<>();
        byte[] csv = inProcess("orders-200k.csv");
        byte[] parquet = inProcess("orders-1m.parquet");
        byte[] xlsx = inProcess("orders-20k.xlsx");
        parse.put("orders-200k.csv", () -> preview.delimited(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(csv), StandardCharsets.UTF_8), 64 * 1024), "csv", 0, 100));
        parse.put("orders-1m.parquet", () -> preview.parquet(parquet, 0, 100));
        parse.put("orders-20k.xlsx", () -> preview.workbook(xlsx, null, 0, 100));
        parse.put("exports-1000.zip", () -> { });

        System.out.println();
        System.out.printf("HEAD (authorisation's metadata read): %d ms median%n",
            medianMillis(() -> s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key("orders-200k.csv").build())));
        System.out.printf("presign alone (local signing):        %d ms median%n", medianMillis(() -> presigner.presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key("orders-200k.csv").build()).build())));
        System.out.println();
        System.out.printf("%-20s %9s %11s %11s %11s %9s%n", "object", "MB", "in-process", "presigned", "proxied", "parse");
        for (Map.Entry<String, Long> file : SIZES.entrySet()) {
            String key = file.getKey();
            long inProcess = medianMillis(() -> inProcess(key));
            long presigned = medianMillis(() -> presigned(key));
            long proxied = medianMillis(() -> proxied(key));
            long parsed = medianMillis(parse.get(key));
            System.out.printf("%-20s %9.1f %9d ms %9d ms %9d ms %6d ms%n", key, file.getValue() / 1048576.0,
                inProcess, presigned, proxied, parsed);
        }
        if (relay != null) relay.stop(0);
    }
}
