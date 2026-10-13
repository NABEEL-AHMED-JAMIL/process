package process.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-355: quiet hours on the zone's wall clock, across midnight too; never quiet without both ends. */
class QuietHoursTest {

    static final LocalTime TEN_PM = LocalTime.of(22, 0);
    static final LocalTime SEVEN_AM = LocalTime.of(7, 0);

    @Test
    void acrossMidnightInChicago() {
        // 2026-10-13 is CDT (UTC-5): 03:30Z is 22:30 the day before, 12:00Z is 07:00, 11:59Z is 06:59.
        assertThat(QuietHours.quiet(TEN_PM, SEVEN_AM, "America/Chicago", Instant.parse("2026-10-14T03:30:00Z"))).isTrue();
        assertThat(QuietHours.quiet(TEN_PM, SEVEN_AM, "America/Chicago", Instant.parse("2026-10-13T11:59:00Z"))).isTrue();
        assertThat(QuietHours.quiet(TEN_PM, SEVEN_AM, "America/Chicago", Instant.parse("2026-10-13T12:00:00Z"))).isFalse();
        assertThat(QuietHours.quiet(TEN_PM, SEVEN_AM, "America/Chicago", Instant.parse("2026-10-13T20:00:00Z"))).isFalse();
    }

    @Test
    void withinOneDayAndInAnotherZone() {
        LocalTime noon = LocalTime.of(12, 0);
        LocalTime two = LocalTime.of(14, 0);
        assertThat(QuietHours.quiet(noon, two, "Europe/Berlin", Instant.parse("2026-10-13T10:30:00Z"))).isTrue();
        assertThat(QuietHours.quiet(noon, two, "Europe/Berlin", Instant.parse("2026-10-13T12:00:00Z"))).isFalse();
    }

    @Test
    void neverQuietWithoutBothEndsOrWithAnUnknownZoneFallingBackToChicago() {
        assertThat(QuietHours.quiet(null, SEVEN_AM, null, Instant.parse("2026-10-13T11:00:00Z"))).isFalse();
        assertThat(QuietHours.quiet(TEN_PM, TEN_PM, null, Instant.parse("2026-10-14T03:30:00Z"))).isFalse();
        assertThat(QuietHours.quiet(TEN_PM, SEVEN_AM, "Not/AZone", Instant.parse("2026-10-14T03:30:00Z"))).isTrue();
    }
}
