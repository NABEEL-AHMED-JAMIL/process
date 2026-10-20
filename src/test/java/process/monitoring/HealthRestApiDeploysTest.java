package process.monitoring;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Owner 2026-10-19: a service's row says its newest deploy, whatever order the windows come in. */
class HealthRestApiDeploysTest {

    @Test
    void theNewestWindowIsTheOneThatStartedLast() {
        Instant now = Instant.parse("2026-10-19T20:00:00Z");
        DeployWindow old = new DeployWindow("media", now.minusSeconds(7200), now.minusSeconds(6300), now.minusSeconds(7100), "ok");
        DeployWindow failed = new DeployWindow("media", now.minusSeconds(600), now.plusSeconds(300), now.minusSeconds(500), "failed");
        DeployWindow middle = new DeployWindow("media", now.minusSeconds(3600), now.minusSeconds(2700), now.minusSeconds(3500), "ok");
        assertSame(failed, HealthRestApi.newest(List.of(old, failed, middle)));
        assertSame(failed, HealthRestApi.newest(List.of(failed, middle, old)));
    }
}
