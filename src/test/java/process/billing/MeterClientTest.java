package process.billing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The console's line to the meter: batched, keyed, never in the caller's way, and a no-op
 * when there is no meter to talk to.
 */
class MeterClientTest {

    @Test
    void reportsQueueAndFlushAsOneBatchWithTheServiceKey() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://meter:8200/v1/events")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Service-Key", "k"))
            .andExpect(request -> {
                JsonObject body = JsonParser.parseString(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()).getAsJsonObject();
                JsonArray events = body.getAsJsonArray("events");
                assertThat(events).hasSize(2);
                JsonObject first = events.get(0).getAsJsonObject();
                assertThat(first.get("meter").getAsString()).isEqualTo("storage.bytes.deleted");
                assertThat(first.get("dedupeKey").getAsString()).isEqualTo("console#1");
                assertThat(first.get("subjectId").getAsString()).isEqualTo("b/claims/a.txt");
            })
            .andRespond(withSuccess("{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}", MediaType.APPLICATION_JSON));

        MeterClient client = new MeterClient("http://meter:8200", "k", http);
        client.report(UsageEvent.of(2905L, "storage.bytes.deleted", 0.5, "GB", "console#1").subject("object", "b/claims/a.txt"));
        client.report(UsageEvent.of(2905L, "storage.ops.delete", 1, "op", "console#2"));
        // Never reported: no workspace, or nothing used.
        client.report(UsageEvent.of(null, "storage.ops.delete", 1, "op", "console#3"));
        client.report(UsageEvent.of(2905L, "storage.ops.delete", 0, "op", "console#4"));

        assertThat(client.flush()).isEqualTo(2);
        server.verify();
    }

    @Test
    void aMeterThatIsDownCostsAWarningNotAnException() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("http://meter:8200/v1/events")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        MeterClient client = new MeterClient("http://meter:8200", "k", http);
        client.report(UsageEvent.of(2905L, "pipeline.runs", 1, "run", "run#1"));
        assertThat(client.flush()).isEqualTo(0);
        server.verify();
    }

    @Test
    void unconfiguredMeansNothingIsSentAndReadsAreEmpty() {
        MeterClient client = new MeterClient("", "", new RestTemplate());
        assertThat(client.isConfigured()).isFalse();
        client.report(UsageEvent.of(2905L, "pipeline.runs", 1, "run", "run#1"));
        assertThat(client.flush()).isEqualTo(0);
        assertThat(client.rateCard()).isEmpty();
    }

    @Test
    void bytesReadAsTheGbTheMetersArePricedIn() {
        assertThat(UsageEvent.gb(1024L * 1024 * 1024)).isEqualTo(1.0);
        assertThat(UsageEvent.gb(512L * 1024 * 1024)).isEqualTo(0.5);
    }
}
