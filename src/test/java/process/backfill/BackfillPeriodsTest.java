package process.backfill;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG-347: a backfill's periods in the workspace's billing zone -- across DST, month and year ends -- and its caps. */
class BackfillPeriodsTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final Instant LATER = Instant.parse("2030-01-01T00:00:00Z");

    static List<BackfillPeriods.Period> days(String from, String to) {
        return BackfillPeriods.of(LocalDate.parse(from), LocalDate.parse(to), BackfillPeriods.Step.DAILY, CHICAGO, LATER);
    }

    @Test
    void aDayIsMidnightToMidnightInTheZoneSoDstDaysAreShortAndLong() {
        List<BackfillPeriods.Period> march = days("2026-03-07", "2026-03-09");
        assertThat(march).extracting(p -> Duration.between(p.start, p.end).toHours()).containsExactly(24L, 23L, 24L);
        assertThat(march.get(1).start).isEqualTo(Instant.parse("2026-03-08T06:00:00Z"));
        assertThat(march.get(1).end).isEqualTo(Instant.parse("2026-03-09T05:00:00Z"));
        List<BackfillPeriods.Period> november = days("2026-10-31", "2026-11-02");
        assertThat(november).extracting(p -> Duration.between(p.start, p.end).toHours()).containsExactly(24L, 25L, 24L);
        assertThat(november.get(1).date).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    void monthAndYearEndsAndALeapDayAreEachOneDay() {
        assertThat(days("2028-02-27", "2028-03-01")).extracting(p -> p.date.toString())
            .containsExactly("2028-02-27", "2028-02-28", "2028-02-29", "2028-03-01");
        assertThat(days("2026-12-30", "2027-01-02")).extracting(p -> p.date.toString())
            .containsExactly("2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02");
        List<BackfillPeriods.Period> april = days("2026-04-30", "2026-05-01");
        assertThat(april.get(0).end).isEqualTo(april.get(1).start);
    }

    @Test
    void theFallBackDayHasTwentyFiveHoursAndTheSpringOneTwentyThree() {
        List<BackfillPeriods.Period> back = BackfillPeriods.of(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"),
            BackfillPeriods.Step.HOURLY, CHICAGO, LATER);
        assertThat(back).hasSize(25);
        // Both 01:00 hours are periods of their own, each an hour long and both on the same day.
        List<String> ones = back.stream().filter(p -> p.start.atZone(CHICAGO).getHour() == 1).map(p -> p.start.toString())
            .collect(Collectors.toList());
        assertThat(ones).containsExactly("2026-11-01T06:00:00Z", "2026-11-01T07:00:00Z");
        assertThat(back).allMatch(p -> p.date.equals(LocalDate.parse("2026-11-01")));
        List<BackfillPeriods.Period> spring = BackfillPeriods.of(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08"),
            BackfillPeriods.Step.HOURLY, CHICAGO, LATER);
        assertThat(spring).hasSize(23);
        assertThat(spring).noneMatch(p -> p.start.atZone(CHICAGO).getHour() == 2);
    }

    @Test
    void aWeekIsSevenDaysFromEachStartDay() {
        List<BackfillPeriods.Period> weeks = BackfillPeriods.of(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-20"),
            BackfillPeriods.Step.WEEKLY, CHICAGO, LATER);
        assertThat(weeks).extracting(p -> p.date.toString()).containsExactly("2026-10-01", "2026-10-08", "2026-10-15");
        assertThat(weeks.get(2).end).isEqualTo(LocalDate.parse("2026-10-22").atStartOfDay(CHICAGO).toInstant());
        // The week of the fall-back: 7 days, 169 hours.
        assertThat(Duration.between(weeks.get(2).start, weeks.get(2).end).toHours()).isEqualTo(168);
        List<BackfillPeriods.Period> dst = BackfillPeriods.of(LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-29"),
            BackfillPeriods.Step.WEEKLY, CHICAGO, LATER);
        assertThat(Duration.between(dst.get(0).start, dst.get(0).end).toHours()).isEqualTo(169);
    }

    @Test
    void anHourThatHasNotBegunIsNotAPeriod() {
        Instant now = Instant.parse("2026-10-13T15:30:00Z");
        List<BackfillPeriods.Period> today = BackfillPeriods.of(LocalDate.parse("2026-10-13"), LocalDate.parse("2026-10-13"),
            BackfillPeriods.Step.HOURLY, CHICAGO, now);
        // 00:00 to 10:00 Chicago (15:00Z) have begun: 11 hours.
        assertThat(today).hasSize(11);
        assertThat(today.get(10).start).isEqualTo(Instant.parse("2026-10-13T15:00:00Z"));
    }

    @Test
    void aBackfillHasAtMost366Periods() {
        assertThat(days("2025-01-01", "2026-01-01")).hasSize(366);
        assertThatThrownBy(() -> days("2025-01-01", "2026-01-02")).hasMessageContaining("a backfill has at most 366");
        assertThatThrownBy(() -> BackfillPeriods.of(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-16"), BackfillPeriods.Step.HOURLY,
            CHICAGO, LATER)).hasMessage("That is 384 hourly periods; a backfill has at most 366. Pick a shorter range or a longer step.");
        assertThatThrownBy(() -> BackfillPeriods.of(LocalDate.parse("2000-01-01"), LocalDate.parse("2026-01-01"), BackfillPeriods.Step.HOURLY,
            CHICAGO, LATER)).hasMessageContaining("a backfill has at most 366");
        assertThatThrownBy(() -> days("2026-10-02", "2026-10-01")).hasMessage("The range ends before it starts.");
    }

    @Test
    void theStepIsReadInAnyCase() {
        assertThat(BackfillPeriods.Step.of("hourly")).contains(BackfillPeriods.Step.HOURLY);
        assertThat(BackfillPeriods.Step.of(" Weekly ")).contains(BackfillPeriods.Step.WEEKLY);
        assertThat(BackfillPeriods.Step.of("monthly")).isEmpty();
    }
}
