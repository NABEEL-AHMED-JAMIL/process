package process.slo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.barco.notifications.contract.NotificationCreated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.identity.IdentityPort;
import process.notifications.NotificationPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-196: the error-budget burn rates of both SLOs under the four multiwindow rules, and the alert to platform
 * administrators -- when a rule holds, to whom, and at most once per SLO per rule per long window.
 */
class SloBurnAlertsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    /** An SLI whose counts are set per window length; every window not set counts nothing. */
    static final class FakeSli implements SloBurnRates.Sli {
        final String key;
        final Duration lag;
        final Map<Duration, SloBurnRates.Counts> byLength = new HashMap<>();
        final List<Instant[]> asked = new ArrayList<>();
        boolean configured = true;
        RuntimeException failure;

        FakeSli(String key, Duration lag) {
            this.key = key;
            this.lag = lag;
        }

        FakeSli counts(Duration window, long good, long bad) {
            this.byLength.put(window, new SloBurnRates.Counts(good, bad));
            return this;
        }

        @Override public String key() { return this.key; }
        @Override public String title() { return this.key.equals("pipeline_execution") ? "Pipeline execution" : "Billing"; }
        @Override public double target() { return 0.9999; }
        @Override public Duration lag() { return this.lag; }
        @Override public Optional<SloBurnRates.Counts> measure(Instant from, Instant to) {
            if (this.failure != null) {
                throw this.failure;
            }
            this.asked.add(new Instant[] {from, to});
            if (!this.configured) {
                return Optional.empty();
            }
            return Optional.of(this.byLength.getOrDefault(Duration.between(from, to), new SloBurnRates.Counts(0, 0)));
        }
    }

    /** The shedlock row, in memory: a name is taken once until its hold passes (never, here). */
    static final class FakeOnce implements SloBurnAlerts.Once {
        final Set<String> held = new HashSet<>();
        final List<Duration> holds = new ArrayList<>();

        @Override
        public boolean first(String name, Duration holdFor) {
            this.holds.add(holdFor);
            return this.held.add(name);
        }
    }

    private final FakeSli runs = new FakeSli("pipeline_execution", Duration.ZERO);
    private final FakeSli billing = new FakeSli("billing_events_priced", Duration.ofMinutes(5));
    private final SloBurnRates rates = new SloBurnRates(Arrays.asList(this.runs, this.billing), Clock.fixed(NOW, ZoneOffset.UTC));
    private final FakeOnce once = new FakeOnce();
    private final IdentityPort identity = mock(IdentityPort.class);
    private final NotificationPort notifications = mock(NotificationPort.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SloBurnAlerts alerts = new SloBurnAlerts(this.rates, this.once, this.identity, this.notifications, this.registry, true);

    SloBurnAlertsTest() {
        when(this.identity.members(any())).thenReturn(Arrays.asList(
            new IdentityPort.Person(1000L, null, "root", "Root", "PLATFORM_ADMIN", "Active"),
            new IdentityPort.Person(4416L, null, "ops", "Ops", "PLATFORM_ADMIN", "Active"),
            new IdentityPort.Person(4417L, null, "gone", "Gone", "PLATFORM_ADMIN", "Delete"),
            new IdentityPort.Person(77L, 12L, "admin", "Workspace admin", "TENANT_ADMIN", "Active"),
            new IdentityPort.Person(78L, 12L, "user", "Workspace user", "TENANT_USER", "Active")));
    }

    private SloBurnRates.Evaluation of(String sli, SloBurnRates.Rule rule) {
        return this.rates.evaluate().stream().filter(e -> e.sli.key().equals(sli) && e.rule == rule).findFirst().get();
    }

    /** 20 bad of 10,000 in the hour, 2 of 800 in the last five minutes: 20x and 25x, above the page's 14.4x. */
    private void aFastBurnOfRuns() {
        this.runs.counts(Duration.ofHours(1), 9_980, 20).counts(Duration.ofMinutes(5), 798, 2);
    }

    @Test
    void aBurnRateIsTheErrorRatioOverTheBudgetsRatio() {
        this.aFastBurnOfRuns();
        SloBurnRates.Evaluation fast = this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H);
        assertThat(fast.longBurn.rate).isCloseTo((20.0 / 10_000) / 0.0001, Offset.offset(1e-9));
        assertThat(fast.shortBurn.rate).isCloseTo((2.0 / 800) / 0.0001, Offset.offset(1e-9));
        assertThat(fast.firing()).isTrue();
        // Nothing counted in a window is no rate at all, not a zero.
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.TICKET_3D).longBurn.rate).isNull();
    }

    @Test
    void theFourRulesAreTheDocumentedPairs() {
        assertThat(Arrays.stream(SloBurnRates.Rule.values()).map(r -> r.threshold + "/" + SloBurnRates.human(r.longWindow) + "/"
            + SloBurnRates.human(r.shortWindow) + "/" + r.severity).collect(Collectors.toList()))
            .containsExactly("14.4/1h/5m/PAGE", "6.0/6h/30m/PAGE", "3.0/1d/2h/TICKET", "1.0/3d/6h/TICKET");
    }

    @Test
    void bothWindowsMustBurnAboveTheThreshold() {
        // The hour burned, the last five minutes did not: it has stopped.
        this.runs.counts(Duration.ofHours(1), 9_980, 20).counts(Duration.ofMinutes(5), 800, 0);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isFalse();
        // The last five minutes burned, the hour is under 14.4x (1 bad in 10,000 is 1x).
        this.runs.counts(Duration.ofHours(1), 9_999, 1).counts(Duration.ofMinutes(5), 799, 1);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isFalse();
        // Just under the threshold does not fire; just over it does.
        this.runs.counts(Duration.ofHours(1), 99_857, 143).counts(Duration.ofMinutes(5), 99_857, 143);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).longBurn.rate).isCloseTo(14.3, Offset.offset(1e-9));
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isFalse();
        this.runs.counts(Duration.ofHours(1), 99_855, 145).counts(Duration.ofMinutes(5), 99_855, 145);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isTrue();
    }

    /** At 99.99% one bad run in a quiet hour is a 10,000x burn; fewer than ten counted never alerts. */
    @Test
    void aQuietWindowDoesNotAlert() {
        this.runs.counts(Duration.ofHours(1), 8, 1).counts(Duration.ofMinutes(5), 0, 1);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isFalse();
        this.runs.counts(Duration.ofHours(1), 9, 1);
        assertThat(this.of("pipeline_execution", SloBurnRates.Rule.PAGE_1H).firing()).isTrue();
    }

    /** Billing's windows end five minutes ago: a younger unrolled event is pending, neither good nor bad. */
    @Test
    void billingsWindowsEndAtItsGrace() {
        this.rates.evaluate();
        assertThat(this.billing.asked).allSatisfy(window -> assertThat(window[1]).isEqualTo(NOW.minus(Duration.ofMinutes(5))));
        assertThat(this.runs.asked).allSatisfy(window -> assertThat(window[1]).isEqualTo(NOW));
        assertThat(this.billing.asked).extracting(window -> Duration.between(window[0], window[1])).containsExactly(
            Duration.ofHours(1), Duration.ofMinutes(5), Duration.ofHours(6), Duration.ofMinutes(30),
            Duration.ofDays(1), Duration.ofHours(2), Duration.ofDays(3), Duration.ofHours(6));
    }

    @Test
    void anSliThatCannotBeMeasuredIsReportedAndDoesNotStopTheOther() {
        this.billing.failure = new IllegalStateException("billing is down");
        this.aFastBurnOfRuns();
        List<SloBurnRates.Evaluation> all = this.rates.evaluate();
        assertThat(all).hasSize(8);
        assertThat(all.stream().filter(e -> e.sli.key().equals("billing_events_priced")))
            .allSatisfy(e -> assertThat(e.unmeasured).isEqualTo("could not be measured"));
        assertThat(this.alerts.checkOnce()).extracting(e -> e.sli.key() + "/" + e.rule).containsExactly("pipeline_execution/PAGE_1H");
        this.billing.failure = null;
        this.billing.configured = false;
        assertThat(this.of("billing_events_priced", SloBurnRates.Rule.PAGE_1H).unmeasured).isEqualTo("not configured here");
        assertThat(this.of("billing_events_priced", SloBurnRates.Rule.PAGE_1H).toMap().get("firing")).isEqualTo(false);
    }

    @Test
    void aFiringRuleTellsEveryLivePlatformAdministratorOnce() {
        this.aFastBurnOfRuns();
        assertThat(this.alerts.checkOnce()).hasSize(1);
        ArgumentCaptor<NotificationCreated> notice = ArgumentCaptor.forClass(NotificationCreated.class);
        verify(this.notifications, times(2)).notificationCreated(eq(null), notice.capture());
        assertThat(notice.getAllValues()).extracting(NotificationCreated::getAppUserId).containsExactly(1000L, 4416L);
        NotificationCreated first = notice.getAllValues().get(0);
        assertThat(first.getType()).isEqualTo("JOB_FAILED");
        assertThat(first.getSeverity()).isEqualTo("ERROR");
        assertThat(first.getLink()).isEqualTo("/administration/reliability");
        assertThat(first.getTitle()).isEqualTo("Pipeline execution is burning its error budget (14.4x)");
        assertThat(first.getBody()).startsWith("20 bad of 10000 counted in the last 1h (20.0x burn), 2 of 800 in the last 5m (25.0x).")
            .contains("28-day error budget of the 99.99% target is spent in 1.4 days").endsWith("Page rule; not repeated for 1h.");
        assertThat(this.once.holds).containsExactly(Duration.ofHours(1));
        assertThat(this.registry.get(SloBurnAlerts.ALERTS).tag("sli", "pipeline_execution").tag("rule", "page_1h").counter().count())
            .isEqualTo(1.0);

        // Still burning five minutes later: the hour's alert has been sent, nothing again.
        assertThat(this.alerts.checkOnce()).isEmpty();
        verify(this.notifications, times(2)).notificationCreated(any(), any());
    }

    @Test
    void aTicketIsAWarningHeldForItsLongWindow() {
        this.billing.counts(Duration.ofDays(3), 99_990, 20).counts(Duration.ofHours(6), 9_990, 10);
        assertThat(this.alerts.checkOnce()).extracting(e -> e.sli.key() + "/" + e.rule).containsExactly("billing_events_priced/TICKET_3D");
        ArgumentCaptor<NotificationCreated> notice = ArgumentCaptor.forClass(NotificationCreated.class);
        verify(this.notifications, times(2)).notificationCreated(any(), notice.capture());
        assertThat(notice.getValue().getSeverity()).isEqualTo("WARNING");
        assertThat(notice.getValue().getTitle()).startsWith("Billing is burning");
        assertThat(this.once.holds).containsExactly(Duration.ofDays(3));
        assertThat(SloBurnAlerts.lockName(this.of("billing_events_priced", SloBurnRates.Rule.TICKET_3D)))
            .isEqualTo("sloBurn:billing_events_priced:TICKET_3D");
    }

    /** Identity unreachable: the alert is not taken, so the next tick sends it. */
    @Test
    void anAlertThatCannotBeAddressedIsNotSpent() {
        this.aFastBurnOfRuns();
        when(this.identity.members(any())).thenThrow(new IdentityPort.Unavailable("identity is down", null));
        this.alerts.check();
        assertThat(this.once.held).isEmpty();
        verify(this.notifications, never()).notificationCreated(any(), any());
    }

    @Test
    void switchedOffItChecksNothing() {
        this.aFastBurnOfRuns();
        new SloBurnAlerts(this.rates, this.once, this.identity, this.notifications, this.registry, false).check();
        assertThat(this.runs.asked).isEmpty();
        verify(this.notifications, never()).notificationCreated(any(), any());
    }

    @Test
    void nothingBurningSendsNothing() {
        this.runs.counts(Duration.ofHours(1), 10_000, 0).counts(Duration.ofMinutes(5), 800, 0);
        assertThat(this.alerts.checkOnce()).isEmpty();
        assertThat(this.once.held).isEmpty();
        verify(this.identity, never()).members(any());
    }
}
