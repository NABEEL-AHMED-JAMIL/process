package process.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.util.BusinessTime;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-163: the business clock's two DST decisions, and that nothing in it follows the JVM's zone.
 */
class BusinessTimeTest {

    @AfterEach
    void systemClock() {
        BusinessTime.useSystemClock();
    }

    @Test
    void aChicagoMorningIsItsInstant() {
        assertThat(BusinessTime.instantOf(LocalDateTime.of(2026, 1, 15, 8, 0))).isEqualTo(Instant.parse("2026-01-15T14:00:00Z"));
        assertThat(BusinessTime.instantOf(LocalDateTime.of(2026, 7, 4, 8, 0))).isEqualTo(Instant.parse("2026-07-04T13:00:00Z"));
        assertThat(BusinessTime.wallClockOf(Instant.parse("2026-01-16T05:30:00Z"))).isEqualTo(LocalDateTime.of(2026, 1, 15, 23, 30));
    }

    @Test
    void theRepeatedHourIsTheSecondOneAsPostgresAndTheOldJvmReadIt() {
        assertThat(BusinessTime.instantOf(LocalDateTime.of(2026, 11, 1, 1, 30))).isEqualTo(Instant.parse("2026-11-01T07:30:00Z"));
        // java.time on its own would say the first, 06:30Z.
        assertThat(LocalDateTime.of(2026, 11, 1, 1, 30).atZone(BusinessTime.ZONE).toInstant()).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    }

    @Test
    void theGapIsMovedOnByItsLength() {
        assertThat(BusinessTime.instantOf(LocalDateTime.of(2026, 3, 8, 2, 30))).isEqualTo(Instant.parse("2026-03-08T08:30:00Z"));
        assertThat(BusinessTime.wallClockOf(Instant.parse("2026-03-08T08:30:00Z"))).isEqualTo(LocalDateTime.of(2026, 3, 8, 3, 30));
    }

    @Test
    void nowIsChicagoWhateverTheJvmSays() {
        BusinessTime.useClock(Clock.fixed(Instant.parse("2026-01-16T05:30:00Z"), ZoneOffset.UTC));
        TimeZone jvm = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Tokyo", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(BusinessTime.now()).as(zone).isEqualTo(LocalDateTime.of(2026, 1, 15, 23, 30));
                assertThat(BusinessTime.today()).as(zone).isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 0).toLocalDate());
                assertThat(BusinessTime.timestampOf(LocalDateTime.of(2026, 1, 15, 8, 0)).toInstant()).as(zone)
                    .isEqualTo(Instant.parse("2026-01-15T14:00:00Z"));
                assertThat(BusinessTime.legacyText(Timestamp.from(Instant.parse("2026-01-16T15:00:00Z")))).as(zone)
                    .isEqualTo("2026-01-16 09:00:00.0");
            }
        } finally {
            TimeZone.setDefault(jvm);
        }
    }

    @Test
    void theLegacyTextIsWhatTimestampPrintedOnTheChicagoJvm() {
        TimeZone jvm = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            for (String instant : new String[] {"2026-01-16T15:00:00Z", "2026-01-16T05:30:05.123Z", "2026-01-16T05:45:10.123456Z",
                "2026-07-04T13:00:00.5Z"}) {
                Timestamp ts = Timestamp.from(Instant.parse(instant));
                String chicagoJvm = ts.toString();
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                assertThat(BusinessTime.legacyText(ts)).as(instant).isEqualTo(chicagoJvm);
                TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            }
        } finally {
            TimeZone.setDefault(jvm);
        }
    }

    @Test
    void theClockAlwaysReadsInTheBusinessZone() {
        BusinessTime.useClock(Clock.fixed(Instant.parse("2026-07-04T13:00:00Z"), ZoneId.of("Asia/Tokyo")));
        assertThat(BusinessTime.now()).isEqualTo(ZonedDateTime.of(2026, 7, 4, 8, 0, 0, 0, BusinessTime.ZONE).toLocalDateTime());
    }
}
