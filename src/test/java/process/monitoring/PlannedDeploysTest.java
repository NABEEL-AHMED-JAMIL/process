package process.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Planned deploy windows: a restart or an unhealthy minute inside one opens no alert, outside one (before, after, or after
 * a window that expired because the deploy never ended it) it does; and how deploy.sh's Redis fields are read.
 */
class PlannedDeploysTest {

    static final Instant NOW = Instant.parse("2026-10-13T15:00:00Z");

    static Instant ago(int minutes) {
        return NOW.minus(Duration.ofMinutes(minutes));
    }

    static AlertRule rule(AlertKind kind, int threshold, int window) {
        AlertRule rule = new AlertRule();
        rule.kind = kind;
        rule.threshold = threshold;
        rule.windowMinutes = window;
        return rule;
    }

    static RuleChecks.Sample sample(String service, int minutesAgo, String status, Instant started) {
        RuleChecks.Sample s = new RuleChecks.Sample();
        s.service = service;
        s.at = ago(minutesAgo);
        s.status = status;
        s.startedAt = started;
        return s;
    }

    /** transcriber's JVM started {@code startedMinutesAgo} minutes ago; sampled each minute since, and an older start before. */
    static Map<String, List<RuleChecks.Sample>> restartedAt(int startedMinutesAgo) {
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        Instant fresh = ago(startedMinutesAgo);
        Instant old = NOW.minus(Duration.ofDays(2));
        recent.put("transcriber", Arrays.asList(sample("transcriber", 0, "UP", fresh), sample("transcriber", startedMinutesAgo - 1, "UP", fresh),
            sample("transcriber", startedMinutesAgo + 1, "UP", old)));
        return recent;
    }

    static Map<String, List<DeployWindow>> windows(DeployWindow... windows) {
        Map<String, List<DeployWindow>> out = new LinkedHashMap<>();
        for (DeployWindow w : windows) {
            out.computeIfAbsent(w.service, k -> new ArrayList<>()).add(w);
        }
        return out;
    }

    static final AlertRule RESTARTED = rule(AlertKind.SERVICE_RESTARTED, 1, 30);
    static final AlertRule UNHEALTHY = rule(AlertKind.SERVICE_UNHEALTHY, 2, 60);

    @Test
    void aRestartInsideItsDeployWindowOpensNoAlert() {
        DeployWindow deploy = new DeployWindow("transcriber", ago(6), ago(6).plus(Duration.ofMinutes(15)), ago(3), "ok");
        assertThat(RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(deploy))).isEmpty();
        // Still deploying (no end yet, not expired): the same.
        DeployWindow running = new DeployWindow("transcriber", ago(6), ago(6).plus(Duration.ofMinutes(15)), null, null);
        assertThat(RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(running))).isEmpty();
    }

    @Test
    void aRestartOutsideEveryWindowAlerts() {
        // No window at all.
        List<Finding> none = RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, Collections.emptyMap());
        assertThat(none).extracting(f -> f.subject).containsExactly("service:transcriber");
        assertThat(none.get(0).title).isEqualTo("transcriber restarted at 09:55");
        assertThat(none.get(0).detail).contains("Not a planned deploy");
        // A window that ended well before the restart.
        DeployWindow earlier = new DeployWindow("transcriber", ago(25), ago(10), ago(20), "ok");
        assertThat(RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(earlier))).hasSize(1);
        // Another service's window does not cover this one.
        DeployWindow other = new DeployWindow("media", ago(6), ago(6).plus(Duration.ofMinutes(15)), ago(3), "ok");
        assertThat(RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(other))).hasSize(1);
    }

    @Test
    void aRestartAfterAnExpiredWindowAlerts() {
        // deploy.sh died: the window was never ended and ran out 4 minutes before the JVM came back.
        DeployWindow forgotten = new DeployWindow("transcriber", ago(25), ago(10), null, null);
        assertThat(forgotten.state(NOW)).isEqualTo("expired");
        List<Finding> found = RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(forgotten));
        assertThat(found).extracting(f -> f.subject).containsExactly("service:transcriber");
        // And a deploy that asks for a day is cut to the longest window: a restart two hours in alerts.
        DeployWindow greedy = new DeployWindow("transcriber", ago(125), NOW.plus(Duration.ofDays(1)), null, null);
        assertThat(greedy.expires).isEqualTo(ago(125).plus(DeployWindow.LONGEST));
        assertThat(RuleChecks.serviceRestarted(RESTARTED, restartedAt(5), NOW, windows(greedy))).hasSize(1);
    }

    @Test
    void anUnplannedStartStillAlertsWhenALaterDeployIsPlanned() {
        Instant crash = ago(20);
        Instant deployed = ago(5);
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        recent.put("transcriber", Arrays.asList(sample("transcriber", 0, "UP", deployed), sample("transcriber", 10, "UP", crash),
            sample("transcriber", 25, "UP", NOW.minus(Duration.ofDays(2)))));
        DeployWindow deploy = new DeployWindow("transcriber", ago(6), ago(6).plus(Duration.ofMinutes(15)), ago(3), "ok");
        List<Finding> found = RuleChecks.serviceRestarted(RESTARTED, recent, NOW, windows(deploy));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).title).isEqualTo("transcriber restarted at 09:40");
    }

    @Test
    void unhealthyMinutesInsideTheWindowAreTheDeployAndOutsideItAlert() {
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        recent.put("transcriber", Arrays.asList(sample("transcriber", 0, "UP", null), sample("transcriber", 1, "UNREACHABLE", null),
            sample("transcriber", 2, "UNREACHABLE", null), sample("transcriber", 3, "UNREACHABLE", null), sample("transcriber", 4, "UP", null)));
        recent.put("media", Arrays.asList(sample("media", 0, "DOWN", null), sample("media", 1, "DOWN", null), sample("media", 2, "UP", null)));
        DeployWindow media = new DeployWindow("media", ago(3), ago(3).plus(Duration.ofMinutes(15)), null, null);
        // media is down while its deploy runs: no alert. Without the window it alerts.
        assertThat(RuleChecks.serviceUnhealthy(UNHEALTHY, recent, windows(media))).isEmpty();
        assertThat(RuleChecks.serviceUnhealthy(UNHEALTHY, recent, Collections.emptyMap())).extracting(f -> f.subject)
            .containsExactly("service:media");
        // Its window expired 5 minutes ago and it is still down: alert.
        DeployWindow expired = new DeployWindow("media", ago(25), ago(5), null, null);
        Map<String, List<RuleChecks.Sample>> stillDown = new LinkedHashMap<>();
        stillDown.put("media", Arrays.asList(sample("media", 0, "DOWN", null), sample("media", 1, "DOWN", null), sample("media", 2, "DOWN", null),
            sample("media", 10, "DOWN", null)));
        assertThat(RuleChecks.serviceUnhealthy(UNHEALTHY, stillDown, windows(expired))).extracting(f -> f.subject)
            .containsExactly("service:media");
    }

    @Test
    void theWindowCoversItsDeployWithAShortGraceAndNoMore() {
        DeployWindow w = new DeployWindow("ai", ago(10), ago(10).plus(Duration.ofMinutes(15)), ago(7), "ok");
        assertThat(w.covers(ago(10).minus(DeployWindow.LEAD))).isTrue();
        assertThat(w.covers(ago(10).minus(DeployWindow.LEAD).minusSeconds(1))).isFalse();
        assertThat(w.covers(ago(7).plus(DeployWindow.GRACE))).isTrue();
        assertThat(w.covers(ago(7).plus(DeployWindow.GRACE).plusSeconds(1))).isFalse();
        assertThat(w.state(NOW)).isEqualTo("ended");
        assertThat(new DeployWindow("ai", ago(1), ago(1).plus(Duration.ofMinutes(15)), null, null).state(NOW)).isEqualTo("open");
    }

    @Test
    void deployShsFieldsAreReadAndOldOrBrokenOnesRemoved() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        long start = ago(4).getEpochSecond();
        long old = NOW.minus(Duration.ofHours(30)).getEpochSecond();
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("transcriber:" + start, "{\"service\":\"transcriber\",\"start\":" + start + ",\"expires\":" + (start + 900)
            + ",\"end\":" + (start + 70) + ",\"outcome\":\"ok\",\"by\":\"deploy.sh\"}");
        fields.put("process:" + (start + 1), "{\"service\":\"process\",\"start\":" + (start + 1) + ",\"expires\":" + (start + 901)
            + ",\"end\":null,\"outcome\":null}");
        fields.put("media:" + old, "{\"service\":\"media\",\"start\":" + old + ",\"expires\":" + (old + 900) + ",\"end\":null}");
        fields.put("junk", "not json");
        fields.put("evil:1", "{\"service\":\"../../x\",\"start\":" + start + ",\"expires\":" + (start + 900) + "}");
        when(hash.entries(PlannedDeploys.KEY)).thenReturn(fields);

        List<DeployWindow> recent = new PlannedDeploys(redis).recent(NOW);

        assertThat(recent).extracting(w -> w.service).containsExactly("process", "transcriber");
        assertThat(recent.get(1).end).isEqualTo(Instant.ofEpochSecond(start + 70));
        assertThat(recent.get(1).outcome).isEqualTo("ok");
        assertThat(recent.get(0).end).isNull();
        verify(hash).delete(PlannedDeploys.KEY, "media:" + old, "junk", "evil:1");
    }

    @Test
    void redisUnreachableMeansNoWindowsSoRestartsAlert() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries(PlannedDeploys.KEY)).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(new PlannedDeploys(redis).byService(NOW)).isEmpty();
        verify(hash).entries(PlannedDeploys.KEY);
        verifyNoMoreInteractions(hash);
    }
}
