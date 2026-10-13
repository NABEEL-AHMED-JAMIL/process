package process.retention;

import org.junit.jupiter.api.Test;
import process.monitoring.AlertKind;
import process.monitoring.AlertRule;
import process.monitoring.Finding;
import process.monitoring.RuleChecks;
import process.monitoring.WorkspaceSignals;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG-354: the rules a pass and the console follow, without a database. */
class RetentionRulesTest {

    private final RetentionService service = new RetentionService(null, null);

    @Test
    void theAuditClassNeverGoesUnderTheFloorAndForeverHasNoCutoff() {
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        assertThat(RetentionPass.cutoff(DataClass.AUDIT, 10, 2190, now)).isEqualTo(now.minus(Duration.ofDays(2190)));
        assertThat(RetentionPass.cutoff(DataClass.AUDIT, 3000, 2190, now)).isEqualTo(now.minus(Duration.ofDays(3000)));
        assertThat(RetentionPass.cutoff(DataClass.FILES, 10, 2190, now)).isEqualTo(now.minus(Duration.ofDays(10)));
        assertThat(RetentionPass.cutoff(DataClass.FILES, null, 2190, now)).isNull();
    }

    @Test
    void theShareIsMeasuredOnTheWholeClass() {
        assertThat(RetentionPass.overShare(20, 100, 20)).isFalse();
        assertThat(RetentionPass.overShare(21, 100, 20)).isTrue();
        assertThat(RetentionPass.overShare(1, 1, 20)).isTrue();
        assertThat(RetentionPass.overShare(0, 0, 20)).isFalse();
        assertThat(RetentionPass.overShare(100, 100, 100)).isFalse();
    }

    @Test
    void aConfirmationCoversAboutAsManyAsTheAdministratorSaw() {
        RetentionStore.Rule rule = new RetentionStore.Rule();
        assertThat(RetentionPass.confirmed(rule, 1)).isFalse();
        rule.confirmedCount = 1000L;
        assertThat(RetentionPass.confirmed(rule, 1100)).isTrue();
        assertThat(RetentionPass.confirmed(rule, 1101)).isFalse();
        rule.confirmedCount = 3L;
        assertThat(RetentionPass.confirmed(rule, 13)).as("at least ten more").isTrue();
        assertThat(RetentionPass.confirmed(rule, 14)).isFalse();
    }

    @Test
    void daysAreCheckedWithTheirSentences() {
        RetentionStore.Platform platform = new RetentionStore.Platform();
        Map<String, Object> classes = new LinkedHashMap<>();
        classes.put("RUN_LOGS", 90);
        classes.put("FILES", "");
        classes.put("NOTIFICATIONS", "forever");
        Map<DataClass, Integer> days = this.service.days(classes, platform, true);
        assertThat(days).containsEntry(DataClass.RUN_LOGS, 90).containsEntry(DataClass.FILES, null).containsEntry(DataClass.NOTIFICATIONS, null);

        assertThatThrownBy(() -> this.service.days(map("RUN_LOGS", 0), platform, true)).hasMessageContaining("1 to 36500");
        assertThatThrownBy(() -> this.service.days(map("RUN_LOGS", 36501), platform, true)).hasMessageContaining("1 to 36500");
        assertThatThrownBy(() -> this.service.days(map("ANALYTICS", 5), platform, true)).hasMessageContaining("bounded by the platform");
        assertThatThrownBy(() -> this.service.days(map("NOPE", 5), platform, true)).hasMessageContaining("dataClass is one of");
        assertThatThrownBy(() -> this.service.days(map("AUDIT", 2189), platform, true)).hasMessageContaining("at least 2190 days");
        assertThat(this.service.days(map("AUDIT", 2189), platform, false)).as("a preview may ask; the pass holds the floor")
            .containsEntry(DataClass.AUDIT, 2189);
    }

    @Test
    void theSchedulerRunsDailyFromTwoChicagoTimeAndResumesWhatStopped() {
        Instant oneAm = Instant.parse("2026-10-13T06:00:00Z");
        Instant threeAm = Instant.parse("2026-10-13T08:00:00Z");
        assertThat(RetentionScheduler.due(null, oneAm)).isNull();
        assertThat(RetentionScheduler.due(null, threeAm)).isEqualTo("SCHEDULE");
        RetentionStore.Pass last = new RetentionStore.Pass();
        last.state = "DONE";
        last.startedAt = Instant.parse("2026-10-13T07:30:00Z");
        assertThat(RetentionScheduler.due(last, threeAm)).as("one a day").isNull();
        last.startedAt = Instant.parse("2026-10-12T07:30:00Z");
        assertThat(RetentionScheduler.due(last, threeAm)).isEqualTo("SCHEDULE");
        last.state = "PARTIAL";
        assertThat(RetentionScheduler.due(last, oneAm)).isEqualTo("RESUME");
        last.state = "INTERRUPTED";
        assertThat(RetentionScheduler.due(last, oneAm)).isEqualTo("RESUME");
        last.state = "RUNNING";
        assertThat(RetentionScheduler.due(last, threeAm)).isNull();
    }

    @Test
    void theAlertNamesEachClassThatPausedOrFailedAndThePassOnlyWhenNoClassSaysIt() {
        AlertRule rule = new AlertRule();
        rule.kind = AlertKind.RETENTION;
        rule.tenantId = 2960L;
        WorkspaceSignals.RetentionTrouble paused = trouble(2960, "RUN_LOGS", "PAUSED", "3 of 4 item(s) would go");
        WorkspaceSignals.RetentionTrouble pass = trouble(2960, null, "FAILED", "removed 0");
        WorkspaceSignals.RetentionTrouble other = trouble(2961, "FILES", "FAILED", "storage down");
        List<Finding> found = RuleChecks.retention(rule, Arrays.asList(paused, pass, other));
        assertThat(found).extracting(f -> f.subject).containsExactly("retention:RUN_LOGS");
        assertThat(found.get(0).title).contains("run logs").contains("confirmation");
        assertThat(RuleChecks.retention(rule, Arrays.asList(pass, other))).extracting(f -> f.subject).containsExactly("retention:pass");
        assertThat(AlertKind.RETENTION.isPlatform()).isFalse();
    }

    private static WorkspaceSignals.RetentionTrouble trouble(long tenant, String dataClass, String state, String note) {
        WorkspaceSignals.RetentionTrouble t = new WorkspaceSignals.RetentionTrouble();
        t.tenantId = tenant;
        t.dataClass = dataClass;
        t.state = state;
        t.note = note;
        t.passId = 1000;
        return t;
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        return out;
    }
}
