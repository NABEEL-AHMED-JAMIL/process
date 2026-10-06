package process.model.service.impl;

import org.junit.jupiter.api.Test;
import process.engine.ProducerBulkEngine;
import process.identity.TestIdentity;
import process.model.dto.ResponseDto;
import process.model.dto.SchedulerDto;
import process.model.repository.AppUserRepository;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.notifications.TestNotifications;
import process.security.TenantFilterHelper;
import process.util.BusinessTime;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-321: the schedule editor's Next runs. The preview copies the timetable onto a Scheduler that is never saved and
 * steps it with the scheduler's own rules, so what it lists is what will run.
 */
class SchedulePreviewTest {

    private final SourceJobServiceImpl service = new SourceJobServiceImpl(mock(SourceJobRepository.class),
        mock(SchedulerRepository.class), mock(SourceTaskRepository.class), mock(JobAuditLogRepository.class),
        mock(JobQueueRepository.class), mock(TaskReferenceRepository.class), TestIdentity.over(mock(AppUserRepository.class), null),
        mock(ProducerBulkEngine.class), mock(TenantFilterHelper.class), mock(OpenSearchAuditLogClient.class),
        TestNotifications.recording(mock(TestNotifications.FeedSink.class), mock(TestNotifications.NoticeSink.class), null),
        mock(UserNameResolver.class));

    private static SchedulerDto timetable(String frequency, String every, LocalDate start, String time) {
        SchedulerDto dto = new SchedulerDto();
        dto.setFrequency(frequency);
        dto.setIntervalValue(every);
        dto.setStartDate(start);
        dto.setStartTime(time == null ? null : LocalTime.parse(time));
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<LocalDateTime> runs(ResponseDto answer) {
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo("SUCCESS");
        return ((List<String>) ((Map<String, Object>) answer.getData()).get("runs")).stream().map(LocalDateTime::parse)
            .collect(Collectors.toList());
    }

    @Test
    void aDailyTimetableListsItsNextFiveRuns() {
        LocalDate start = BusinessTime.today().plusDays(3);

        List<LocalDateTime> runs = runs(this.service.schedulePreview(timetable("Daily", "1", start, "02:00")));

        assertThat(runs).containsExactly(start.atTime(2, 0), start.plusDays(1).atTime(2, 0), start.plusDays(2).atTime(2, 0),
            start.plusDays(3).atTime(2, 0), start.plusDays(4).atTime(2, 0));
    }

    @Test
    void aWeeklyTimetableRunsOnlyOnItsDays() {
        SchedulerDto weekly = timetable("Weekly", "1", BusinessTime.today().plusDays(1), "08:30");
        weekly.setDaysOfWeek("MON,THU");

        List<LocalDateTime> runs = runs(this.service.schedulePreview(weekly));

        assertThat(runs).hasSize(5).allSatisfy(run -> {
            assertThat(run.getDayOfWeek()).isIn(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
            assertThat(run.toLocalTime()).isEqualTo(LocalTime.of(8, 30));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEndDateStopsTheList() {
        LocalDate start = BusinessTime.today().plusDays(2);
        SchedulerDto daily = timetable("Daily", "1", start, "06:00");
        daily.setEndDate(start.plusDays(1));

        ResponseDto answer = this.service.schedulePreview(daily);

        assertThat(runs(answer)).containsExactly(start.atTime(6, 0), start.plusDays(1).atTime(6, 0));
        assertThat(((Map<String, Object>) answer.getData()).get("ends")).isEqualTo(true);
    }

    @Test
    void aCronTimetableListsItsSlots() {
        SchedulerDto cron = timetable("Cron", null, null, null);
        cron.setCronExpression("0 3 * * *");

        assertThat(runs(this.service.schedulePreview(cron))).hasSize(5)
            .allSatisfy(run -> assertThat(run.toLocalTime()).isEqualTo(LocalTime.of(3, 0)));
    }

    @Test
    void anIncompleteTimetableIsToldWhatIsMissing() {
        assertThat(this.service.schedulePreview(timetable("Daily", "1", null, "02:00")).getMessage())
            .isEqualTo("Choose the start date and time.");
        assertThat(this.service.schedulePreview(timetable("Daily", "0", BusinessTime.today(), "02:00")).getMessage())
            .isEqualTo("Repeat every needs a whole number of 1 or more.");
        assertThat(this.service.schedulePreview(timetable("Fortnightly", "1", BusinessTime.today(), "02:00")).getStatus())
            .isEqualTo("ERROR");
        SchedulerDto badCron = timetable("Cron", null, null, null);
        badCron.setCronExpression("not a cron");
        assertThat(this.service.schedulePreview(badCron).getMessage()).startsWith("SourceJob schedule:");
    }
}
