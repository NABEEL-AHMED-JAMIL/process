package process.correlation;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.ai.HttpAi;
import process.config.KafkaConnectionResolver;
import process.model.pojo.KafkaConnectionProfile;
import process.config.KafkaTemplateProvider;
import process.identity.InternalKafkaPublishRestApi;
import process.media.HttpMedia;
import process.model.pojo.JobQueue;
import process.outbox.OutboxRelay;
import process.storage.TrustedAccess;
import process.storage.TrustedCaller;
import process.storage.remote.StorageServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-94: the correlation id of the work in hand goes along on every hop process makes to another part of the
 * estate (16-testing-strategy 8.3; D10), so one grep finds the request in every service it reached.
 *
 * <ul>
 *   <li>HTTP to Media, AI and Storage (OkHttp, the internal token): X-Correlation-Id on every call.</li>
 *   <li>Kafka through {@code /internal/kafka/publish} (Analytics asks Core to publish): the record carries the
 *       asking request's id as its X-Correlation-Id header.</li>
 *   <li>The platform outbox (notifications' contract events): each record carries its event's traceId as the
 *       X-Correlation-Id header too, so a consumer that reads headers, not payloads, finds it (X6).</li>
 * </ul>
 * Third-party calls (report export's user-given target, embeddings, OpenSearch) are deliberately not given it.
 */
class CorrelationHopsTest {

    private static final String ID = "01J8ZK3V4N6T8W0Y2A4C6E8G0J";

    private HttpServer server;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void listen() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            this.seen.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst(CorrelationId.HEADER));
            byte[] body = "{\"status\":\"SUCCESS\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        this.server.start();
        CorrelationId.set(ID);
        // Media is called as the signed-in user, from inside their request.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
        CorrelationId.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private String url() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    private String headerOnlyCall() {
        assertThat(this.seen).as("the call reached the service").hasSize(1);
        return this.seen.get(0).substring(this.seen.get(0).lastIndexOf(' ') + 1);
    }

    @Test
    void aCallToMediaCarriesTheId() {
        new HttpMedia(url(), "service-token").forgetExtraction("etl-bucket", "q3/a.pdf", "e1");

        assertThat(headerOnlyCall()).isEqualTo(ID);
    }

    @Test
    void aCallToAiCarriesTheId() {
        try {
            new HttpAi(url(), "service-token").prompts(Collections.singletonList(7L));
        } catch (Exception answerNotReadable) {
            // only the request matters here
        }

        assertThat(headerOnlyCall()).isEqualTo(ID);
    }

    @Test
    void aCallToStorageCarriesTheId() {
        try {
            new StorageServiceClient(url(), "service-token").trustedRead(
                TrustedAccess.of(TrustedCaller.KAFKA_SECRETS, "truststore").forTenant(2901L), "etl-config", "k/7/t.p12");
        } catch (Exception answerNotReadable) {
            // only the request matters here
        }

        assertThat(headerOnlyCall()).isEqualTo(ID);
    }

    /** Outside any work, nothing is invented: the called service mints its own. */
    @Test
    void noIdMeansNoHeaderNotAnEmptyOne() {
        CorrelationId.clear();

        new HttpMedia(url(), "service-token").forgetExtraction("etl-bucket", "q3/a.pdf", "e1");

        assertThat(headerOnlyCall()).isEqualTo("null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEventAnalyticsAsksCoreToPublishCarriesTheAskingRequestsId() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AtomicReference<ProducerRecord<String, String>> sent = new AtomicReference<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            sent.set(call.getArgument(0));
            SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
            future.set(new SendResult<>(call.getArgument(0),
                new RecordMetadata(new TopicPartition("analytics.query.completed", 0), 0L, 0L, 0L, 0L, 0, 0)));
            return future;
        });
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(kafka);
        KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
        when(resolver.require(any(), any())).thenReturn(new KafkaConnectionProfile());
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", 2905);
        body.put("topic", "analytics.query.completed");
        body.put("key", "q-1");
        body.put("payload", "{}");

        new InternalKafkaPublishRestApi(resolver, templates, "service-token").publish("service-token", body);

        assertThat(sent.get()).isNotNull();
        assertThat(header(sent.get(), CorrelationId.HEADER)).isEqualTo(ID);
    }

    @Test
    void anOutboxRecordCarriesItsEventsTraceIdAsTheHeader() {
        CorrelationId.clear();
        String event = "{\"eventId\":\"e-1\",\"eventType\":\"JOB_STATUS\",\"tenantId\":2905,\"traceId\":\"" + ID
            + "\",\"payload\":{}}";

        ProducerRecord<String, String> record = OutboxRelay.recordOf("platform.job.status.v1", "run:7", event);

        assertThat(record.value()).isEqualTo(event);
        assertThat(record.key()).isEqualTo("run:7");
        assertThat(header(record, CorrelationId.HEADER)).isEqualTo(ID);
    }

    /** An event with no traceId, or one that is not JSON, still goes out -- without a header (X10). */
    @Test
    void anOutboxEventWithoutATraceIdStillGoesOut() {
        ProducerRecord<String, String> none = OutboxRelay.recordOf("t", "k", "{\"eventId\":\"e-1\"}");
        ProducerRecord<String, String> garbled = OutboxRelay.recordOf("t", "k", "not json");
        ProducerRecord<String, String> forged = OutboxRelay.recordOf("t", "k", "{\"traceId\":\"abcdefgh\\nforged\"}");

        assertThat(header(none, CorrelationId.HEADER)).isNull();
        assertThat(header(garbled, CorrelationId.HEADER)).isNull();
        assertThat(header(forged, CorrelationId.HEADER)).isNull();
        assertThat(garbled.value()).isEqualTo("not json");
    }

    // ---- the run's own id -------------------------------------------------------------------------

    /**
     * "Run now" makes the run under the request that asked for it, so the browser's id is the run's id: the
     * dispatch, the worker and every callback are found from the id the console showed (X9's first hop).
     */
    @Test
    void aRunMadeByARequestTakesTheRequestsId() {
        JobQueue run = new JobQueue();

        RunCorrelation.stamp(run);

        assertThat(run.getCorrelationId()).isEqualTo(ID);
    }

    @Test
    void aRunThatAlreadyHasAnIdKeepsIt() {
        JobQueue run = new JobQueue();
        run.setCorrelationId("first-dispatch-0001");

        RunCorrelation.stamp(run);

        assertThat(run.getCorrelationId()).isEqualTo("first-dispatch-0001");
    }

    @Test
    void aRunMadeOutsideAnyWorkIsLeftForDispatchToName() {
        CorrelationId.clear();
        JobQueue run = new JobQueue();

        RunCorrelation.stamp(run);

        assertThat(run.getCorrelationId()).isNull();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static String mdc() {
        return MDC.get(CorrelationId.MDC_KEY);
    }
}
