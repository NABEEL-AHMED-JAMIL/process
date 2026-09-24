package process.billing;

import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.MeterTransport;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The console's line to the meter. Reporting is platform-commons' MeterReporter (MIG-80), tested
 * there -- batching, parking what the meter rejects, spooling what it cannot take; here, that the
 * console's reports reach it, and that no meter means nothing sent and empty reads.
 */
class MeterClientTest {

    @TempDir
    Path spool;

    @Test
    void reportsGoThroughThePlatformReporterWithTheServiceKey() {
        List<String> bodies = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        MeterTransport meter = (url, headers, body) -> {
            bodies.add(new String(body));
            keys.add(headers.get("X-Service-Key"));
            return new MeterTransport.Answer(200, "{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}");
        };
        MeterReporter reporter = new MeterReporter("http://meter:8200", "k", this.spool, meter, 0);
        MeterClient client = new MeterClient("http://meter:8200", "k", new RestTemplate(), reporter);

        client.report(UsageEvent.of(2905L, Meter.STORAGE_BYTES_DELETED, 0.5, "console#1").subject("object", "b/claims/a.txt"));
        client.report(UsageEvent.of(2905L, Meter.STORAGE_OPS_DELETE, 1, "console#2"));
        // Never reported: no workspace, or nothing used.
        client.report(UsageEvent.of(null, Meter.STORAGE_OPS_DELETE, 1, "console#3"));
        client.report(UsageEvent.of(2905L, Meter.STORAGE_OPS_DELETE, 0, "console#4"));
        client.flush();

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"storage.bytes.deleted\"", "\"console#1\"", "\"console#2\"").doesNotContain("console#3", "console#4");
        assertThat(keys).containsExactly("k");
        assertThat(reporter.counts().accepted()).isEqualTo(2);
    }

    /** MIG-15: what used to be a warning and a lost event is parked with the meter's reason. */
    @Test
    void anEventTheMeterRejectsIsKeptNotLost() throws Exception {
        MeterTransport meter = (url, headers, body) ->
            new MeterTransport.Answer(200, "{\"accepted\":0,\"duplicates\":0,\"rejected\":[{\"index\":0,\"reason\":\"unknown meter\"}]}");
        MeterReporter reporter = new MeterReporter("http://meter:8200", "k", this.spool, meter, 0);
        MeterClient client = new MeterClient("http://meter:8200", "k", new RestTemplate(), reporter);

        client.report(UsageEvent.of(2905L, Meter.PIPELINE_RUNS, 1, "run#1"));
        client.flush();

        try (Stream<Path> dead = Files.list(this.spool.resolve("dead"))) {
            assertThat(dead.count()).isEqualTo(1);
        }
        assertThat(reporter.counts().rejected()).isEqualTo(1);
    }

    @Test
    void unconfiguredMeansNothingIsSentAndReadsAreEmpty() {
        List<String> bodies = new ArrayList<>();
        MeterReporter reporter = new MeterReporter("", "", this.spool, (url, headers, body) -> {
            bodies.add(new String(body));
            return new MeterTransport.Answer(200, "{}");
        }, 0);
        MeterClient client = new MeterClient("", "", new RestTemplate(), reporter);
        assertThat(client.isConfigured()).isFalse();
        client.report(UsageEvent.of(2905L, Meter.PIPELINE_RUNS, 1, "run#1"));
        client.flush();
        assertThat(bodies).isEmpty();
        assertThat(client.rateCard()).isEmpty();
    }

    @Test
    void noReporterAtAllIsANoOpToo() {
        MeterClient client = new MeterClient("http://meter:8200", "k", new RestTemplate(), null);
        client.report(UsageEvent.of(2905L, Meter.PIPELINE_RUNS, 1, "run#1"));
        client.flush();
    }

    /**
     * MIG-197: a quantity at the ledger's full scale reaches the invoice with every digit. Read as a
     * double it kept 15 to 17 significant digits; 123456789012.123456 has 18.
     */
    @Test
    void theMetersNumbersAreReadAsExactDecimals() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo(startsWith("http://meter:8200/v1/usage"))).andRespond(withSuccess(
            "{\"rows\":[{\"meter\":\"storage.bytes.read\",\"quantity\":123456789012.123456,\"amount\":0.00602,"
                + "\"includedQuantity\":1000.000001,\"per\":1073741824}]}", MediaType.APPLICATION_JSON));
        MeterClient client = new MeterClient("http://meter:8200", "k", http, null);

        Map<String, Object> row = rows(client.usage(2905L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "meter")).get(0);

        assertThat(row.get("quantity")).isEqualTo(new BigDecimal("123456789012.123456"));
        assertThat(row.get("includedQuantity")).isEqualTo(new BigDecimal("1000.000001"));
        assertThat(row.get("amount")).isEqualTo(new BigDecimal("0.00602"));
        assertThat(row.get("per")).isEqualTo(1073741824);
        server.verify();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("rows");
    }

    @Test
    void bytesReadAsTheGbTheMetersArePricedIn() {
        assertThat(UsageEvent.gb(1024L * 1024 * 1024)).isEqualTo(1.0);
        assertThat(UsageEvent.gb(512L * 1024 * 1024)).isEqualTo(0.5);
    }
}
