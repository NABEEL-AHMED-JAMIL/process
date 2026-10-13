package process.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MIG-355: what a rule may say -- each refusal a sentence -- and what the console's body becomes. */
class AlertRulesTest {

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final AlertRules rules = new AlertRules(this.jdbc);

    static AlertRule rule(AlertKind kind) {
        AlertRule rule = new AlertRule();
        rule.kind = kind;
        rule.threshold = AlertRules.defaultThreshold(kind);
        rule.windowMinutes = AlertRules.defaultWindow(kind);
        rule.roles = new LinkedHashSet<>(Collections.singletonList("TENANT_ADMIN"));
        return rule;
    }

    private void refused(AlertRule rule, Long tenant, String says) {
        assertThatThrownBy(() -> this.rules.checked(rule, tenant)).isInstanceOf(AlertRules.Refused.class).hasMessageContaining(says)
            .matches(e -> ((AlertRules.Refused) e).getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void aWorkspaceRuleIsAWorkspacesKindAndAPlatformRuleThePlatforms() {
        refused(rule(AlertKind.DISK_LOW), 2960L, "is the platform's to watch");
        refused(rule(AlertKind.RUN_FAILED), null, "The platform's rules watch services");
        AlertRule platform = this.rules.checked(rule(AlertKind.SERVICE_UNHEALTHY), null);
        assertThat(platform.roles).containsExactly("PLATFORM_ADMIN");
        assertThat(platform.name).isEqualTo("A service is unhealthy");
    }

    @Test
    void eachKindsNumbersAreInRange() {
        AlertRule burst = rule(AlertKind.RUN_FAILED);
        burst.threshold = 0;
        refused(burst, 2960L, "threshold (failed runs) is 1 to 1000");
        AlertRule slow = rule(AlertKind.RUN_SLOW);
        slow.factor = new BigDecimal("1.1");
        refused(slow, 2960L, "factor is 1.2 to 20");
        slow.factor = null;
        assertThat(this.rules.checked(slow, 2960L).factor).isEqualByComparingTo("2");
        AlertRule memory = rule(AlertKind.MEMORY_HIGH);
        memory.threshold = 100;
        refused(memory, null, "threshold (percent of the heap) is 50 to 99");
        AlertRule waiting = rule(AlertKind.REVIEW_WAITING);
        waiting.windowMinutes = 1;
        refused(waiting, 2960L, "windowMinutes (how long a review may wait) is 5 to 10080");
    }

    @Test
    void someoneHearsOfItAndOnlyTheWorkspacesRolesAndGroups() {
        AlertRule nobody = rule(AlertKind.RUN_FAILED);
        nobody.roles = new LinkedHashSet<>();
        refused(nobody, 2960L, "Name who hears of it");
        nobody.bell = false;
        nobody.webhook = true;
        assertThat(this.rules.checked(nobody, 2960L).webhook).isTrue();
        AlertRule platformRole = rule(AlertKind.RUN_FAILED);
        platformRole.roles = new LinkedHashSet<>(Collections.singletonList("PLATFORM_ADMIN"));
        refused(platformRole, 2960L, "roles are the workspace's: tenant administrators or tenant users");
        AlertRule silent = rule(AlertKind.RUN_FAILED);
        silent.bell = false;
        refused(silent, 2960L, "at least one way to hear of it");
        AlertRule platformHook = rule(AlertKind.DISK_LOW);
        platformHook.webhook = true;
        refused(platformHook, null, "go to no webhook");
        AlertRule groups = rule(AlertKind.RUN_FAILED);
        groups.groups = new LinkedHashSet<>();
        for (long g = 1; g <= 21; g++) {
            groups.groups.add(g);
        }
        refused(groups, 2960L, "At most 20 groups");
    }

    @Test
    void aPipelineMustBeTheWorkspacesOwn() {
        when(this.jdbc.queryForObject(anyString(), eq(Integer.class), eq(42L), eq(2960L))).thenReturn(1);
        when(this.jdbc.queryForObject(anyString(), eq(Integer.class), eq(43L), eq(2960L))).thenReturn(0);
        AlertRule mine = rule(AlertKind.RUN_FAILED);
        mine.jobId = 42L;
        assertThat(this.rules.checked(mine, 2960L).jobId).isEqualTo(42L);
        AlertRule theirs = rule(AlertKind.RUN_FAILED);
        theirs.jobId = 43L;
        refused(theirs, 2960L, "No such pipeline in this workspace");
    }

    @Test
    void quietHoursNeedBothEndsAndARealZone() {
        AlertRule half = rule(AlertKind.RUN_FAILED);
        half.quietStart = LocalTime.of(22, 0);
        refused(half, 2960L, "a start and an end");
        half.quietEnd = LocalTime.of(7, 0);
        half.quietZone = "Mars/Olympus";
        refused(half, 2960L, "No such time zone");
        half.quietZone = null;
        assertThat(this.rules.checked(half, 2960L).quietZone).isEqualTo("America/Chicago");
    }

    @Test
    void theConsolesBodyBecomesARule() {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "run_failed");
        body.put("threshold", 3);
        body.put("windowMinutes", "10");
        Map<String, Object> channels = new HashMap<>();
        channels.put("bell", true);
        channels.put("email", true);
        channels.put("webhook", false);
        body.put("channels", channels);
        body.put("roles", Arrays.asList("TENANT_ADMIN", "tenant_user"));
        body.put("groups", Collections.singletonList(8601));
        Map<String, Object> quiet = new HashMap<>();
        quiet.put("start", "22:00");
        quiet.put("end", "07:00");
        body.put("quietHours", quiet);
        AlertRule rule = this.rules.checked(AlertRules.fromBody(body), 2960L);
        assertThat(rule.kind).isEqualTo(AlertKind.RUN_FAILED);
        assertThat(rule.threshold).isEqualTo(3);
        assertThat(rule.windowMinutes).isEqualTo(10);
        assertThat(rule.email).isTrue();
        assertThat(rule.roles).containsExactly("TENANT_ADMIN", "TENANT_USER");
        assertThat(rule.groups).containsExactly(8601L);
        assertThat(rule.quietStart).isEqualTo(LocalTime.of(22, 0));
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("kind", "nope");
        assertThatThrownBy(() -> AlertRules.fromBody(unknown)).isInstanceOf(AlertRules.Refused.class).hasMessageContaining("kind is one of");
        assertThat(this.rules.checked(AlertRules.fromBody(Collections.singletonMap("kind", "RUN_FAILED")), 2960L).roles)
            .containsExactly("TENANT_ADMIN");
    }

    @Test
    void theKindsTheConsoleOffersAreTheScopes() {
        assertThat(AlertRestApi.kinds(false)).extracting(k -> k.get("kind")).containsExactly("RUN_FAILED", "SCHEDULE_MISSED", "RUN_SLOW",
            "REVIEW_WAITING");
        assertThat(AlertRestApi.kinds(true)).hasSize(6).allMatch(k -> String.valueOf(k.get("runbook")).startsWith("docs/runbooks/alerts.md#"));
    }
}
