package process.monitoring;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** MIG-355: the figures a service's Prometheus page holds, read line by line; the actuator's own calls left out. */
class ServiceReadingTest {

    static final String PAGE = String.join("\n",
        "# HELP jvm_memory_used_bytes The amount of used memory",
        "# TYPE jvm_memory_used_bytes gauge",
        "jvm_memory_used_bytes{area=\"heap\",id=\"G1 Eden Space\",} 1.048576E8",
        "jvm_memory_used_bytes{area=\"heap\",id=\"G1 Old Gen\",} 2.097152E8",
        "jvm_memory_used_bytes{area=\"nonheap\",id=\"Metaspace\",} 9.0E7",
        "jvm_memory_max_bytes{area=\"heap\",id=\"G1 Eden Space\",} -1.0",
        "jvm_memory_max_bytes{area=\"heap\",id=\"G1 Old Gen\",} 1.073741824E9",
        "jvm_memory_usage_after_gc_percent{area=\"heap\",pool=\"long-lived\",} 0.25",
        "process_start_time_seconds 1.7603E9",
        "process_cpu_usage 0.031",
        "jvm_threads_live_threads 57.0",
        "http_server_requests_seconds_count{exception=\"None\",method=\"GET\",outcome=\"SUCCESS\",status=\"200\",uri=\"/x\",} 90.0",
        "http_server_requests_seconds_sum{exception=\"None\",method=\"GET\",outcome=\"SUCCESS\",status=\"200\",uri=\"/x\",} 4.5",
        "http_server_requests_seconds_count{exception=\"Boom\",method=\"POST\",outcome=\"SERVER_ERROR\",status=\"500\",uri=\"/y\",} 10.0",
        "http_server_requests_seconds_sum{exception=\"Boom\",method=\"POST\",outcome=\"SERVER_ERROR\",status=\"500\",uri=\"/y\",} 0.5",
        "http_server_requests_seconds_count{exception=\"None\",method=\"GET\",outcome=\"SUCCESS\",status=\"200\",uri=\"/actuator/prometheus\",} 1000.0",
        "http_server_requests_seconds_bucket{exception=\"None\",le=\"0.1\",uri=\"/x\",} 80.0");

    @Test
    void theHeapStartAndRequestsAreReadAndCpuThreadsAndLatencyAreNot() throws Exception {
        ServiceReading r = new ServiceReading();
        ServiceReading.parsePrometheus(new BufferedReader(new StringReader(PAGE)), r);
        assertThat(r.heapUsedBytes).isEqualTo(3.145728E8);
        assertThat(r.heapMaxBytes).isEqualTo(1.073741824E9);
        assertThat(r.heapAfterGcPct).isCloseTo(25.0, within(0.001));
        assertThat(r.startedAt).isEqualTo(Instant.ofEpochSecond(1_760_300_000L));
        assertThat(r.requests).isEqualTo(100.0);
        assertThat(r.errors).isEqualTo(10.0);
    }

    @Test
    void labelsAreReadByNameNotBySuffix() {
        String line = "http_server_requests_seconds_count{exception=\"None\",status=\"503\",uri=\"/a\",} 1.0";
        assertThat(ServiceReading.label(line, "status")).isEqualTo("503");
        assertThat(ServiceReading.label(line, "uri")).isEqualTo("/a");
        assertThat(ServiceReading.label(line, "missing")).isNull();
        assertThat(ServiceReading.known("up")).isEqualTo("UP");
        assertThat(ServiceReading.known("weird")).isEqualTo("UNKNOWN");
    }

    @Test
    void aTargetListIsParsedAndBadEntriesSkipped() {
        assertThat(HealthCollector.parse(HealthCollector.DEFAULT_TARGETS)).hasSize(13);
        assertThat(HealthCollector.parse("a=http://a:1/actuator/|prometheus, broken, b=self|self")).extracting(t -> t.name + " " + t.base + " " + t.mode)
            .containsExactly("a http://a:1/actuator prometheus", "b self self");
    }
}
