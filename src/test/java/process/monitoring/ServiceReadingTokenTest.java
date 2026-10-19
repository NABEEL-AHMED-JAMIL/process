package process.monitoring;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;

/**
 * Vulnerabilities review 2026-10-13, C7: the services' metrics answer only the platform's internal token, so the sampler
 * (MIG-355) sends it on every metrics read -- the Prometheus page and the /metrics/{name} answers -- and its readings
 * keep arriving. Here a stand-in service answers 401 without the token, as the services' ActuatorGuard does.
 */
class ServiceReadingTokenTest {

    private static final String TOKEN = "internal-token-for-this-test";
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/v1/actuator/", exchange -> {
            boolean allowed = TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            String path = exchange.getRequestURI().getPath();
            String body = !allowed ? "" : path.endsWith("/prometheus") ? "process_start_time_seconds 1.7603E9\n"
                : path.contains("/metrics/") ? "{\"name\":\"x\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":42.0}]}" : "";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(allowed ? 200 : 401, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        this.server.start();
        this.base = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/api/v1/actuator";
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    @Test
    void thePrometheusPageIsReadWithTheTokenAndRefusedWithout() {
        HttpClient http = HttpClient.newHttpClient();
        ServiceReading with = new ServiceReading();
        ServiceReading without = new ServiceReading();

        assertThat(ServiceReading.prometheus(http, this.base, with, Duration.ofSeconds(5), TOKEN)).isTrue();
        assertThat(with.startedAt).isEqualTo(Instant.ofEpochSecond(1_760_300_000L));
        assertThat(ServiceReading.prometheus(http, this.base, without, Duration.ofSeconds(5), "")).isFalse();
    }

    @Test
    void theMetricsAnswersAreReadWithTheToken() {
        HttpClient http = HttpClient.newHttpClient();
        ServiceReading with = new ServiceReading();
        ServiceReading without = new ServiceReading();

        assertThat(ServiceReading.metrics(http, this.base, with, Duration.ofSeconds(5), TOKEN)).isTrue();
        assertThat(with.heapUsedBytes).isEqualTo(42.0);
        assertThat(ServiceReading.metrics(http, this.base, without, Duration.ofSeconds(5), null)).isFalse();
    }
}
