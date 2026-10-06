package process.pipeline;

import org.junit.jupiter.api.Test;
import process.ai.AiPort;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-243: how long a run's datasets are kept -- the data policy's days a ceiling on the pipeline's own hours, the
 * policy's days when the pipeline names none, and as before when the policy says nothing or cannot be read.
 */
class PolicyRetentionTest {

    private static PipelineDefinition.Settings settings(Integer hours, String sensitivity) {
        PipelineDefinition.Settings s = new PipelineDefinition.Settings();
        s.setDatasetRetentionHours(hours);
        s.setSensitivity(sensitivity);
        return s;
    }

    @Test
    void thePolicysDaysAreACeilingOnThePipelinesOwnHours() {
        assertThat(RetentionPolicy.combine(48, 1)).isEqualTo(Duration.ofDays(1));
        assertThat(RetentionPolicy.combine(12, 1)).isEqualTo(Duration.ofHours(12));
        assertThat(RetentionPolicy.combine(null, 3)).isEqualTo(Duration.ofDays(3));
        assertThat(RetentionPolicy.combine(48, null)).isEqualTo(Duration.ofHours(48));
        assertThat(RetentionPolicy.combine(null, null)).isEqualTo(Duration.ofHours(24));
        assertThat(RetentionPolicy.DEFINITION_ONLY.retentionFor(1L, settings(null, "sensitive"))).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void theLevelIsThePipelinesSensitivityAndInternalWhenNotSaid() throws Exception {
        AiPort ai = mock(AiPort.class);
        Map<String, Integer> days = new HashMap<>();
        days.put("public", null);
        days.put("internal", 7);
        days.put("sensitive", 2);
        when(ai.retentionDays(5L)).thenReturn(days);
        PolicyRetention retention = new PolicyRetention(ai);
        assertThat(retention.retentionFor(5L, settings(null, "sensitive"))).isEqualTo(Duration.ofDays(2));
        assertThat(retention.retentionFor(5L, settings(null, null))).isEqualTo(Duration.ofDays(7));
        assertThat(retention.retentionFor(5L, settings(null, "public"))).isEqualTo(Duration.ofHours(24));
        assertThat(retention.retentionFor(5L, settings(720, "sensitive"))).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void aWorkspacesPolicyIsReadOnceInFiveMinutesAndAnOutageLeavesThePipelinesOwn() throws Exception {
        AiPort ai = mock(AiPort.class);
        AtomicInteger asked = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000_000L);
        when(ai.retentionDays(anyLong())).thenAnswer(inv -> {
            asked.incrementAndGet();
            if ((Long) inv.getArgument(0) == 9L) {
                throw new AiPort.AiUnavailableException("down", null);
            }
            Map<String, Integer> d = new HashMap<>();
            d.put("internal", 1);
            return d;
        });
        PolicyRetention retention = new PolicyRetention(ai, now::get);
        retention.retentionFor(5L, settings(null, null));
        retention.retentionFor(5L, settings(null, null));
        assertThat(asked).hasValue(1);
        now.addAndGet(PolicyRetention.REMEMBER_MILLIS + 1);
        retention.retentionFor(5L, settings(null, null));
        assertThat(asked).hasValue(2);

        assertThat(retention.retentionFor(9L, settings(36, "sensitive"))).as("ai-service down: the pipeline's own").isEqualTo(Duration.ofHours(36));
        assertThat(retention.retentionFor(9L, settings(null, null))).isEqualTo(Duration.ofHours(24));
        assertThat(asked).as("the outage is remembered for a minute").hasValue(3);
    }
}
