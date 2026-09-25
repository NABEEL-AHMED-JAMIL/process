package process.model.service.impl;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.excel.BulkExcel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Optional;
import process.notifications.TestNotifications;

/**
 * The two bulk paths, on the inputs they were never written for.
 *
 * The upload parsed the Task Id cell with a bare Long.valueOf, so a cell holding the task's name
 * -- or the composite "1043 [orders nightly]" that the job-list download writes into that very
 * column -- threw NumberFormatException out of the method entirely. The controller answered HTTP
 * 400 "Sorry, the file could not be uploaded. Please contact support.", with no row number, no
 * column, and no sign that the other rows were fine, while every other bad cell in the sheet
 * produces a row-numbered message.
 *
 * The download read its schedules one query per exported row, where the jobs list does the same
 * work with a single findByJobIdIn, and dereferenced getTaskDetail() twice with no null check
 * while every neighbouring cell was guarded -- so one legacy row without a task took the whole
 * export down with an HTTP 500.
 */
@ExtendWith(MockitoExtension.class)
public class SourceJobBulkDefectTest {

    private static final long TENANT_A = 1001L;

    @Mock private TransactionServiceImpl transactionService;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private TestNotifications.NoticeSink notificationCenterService;

    private SourceJobBulkServiceImpl service;

    @BeforeEach
    void setUp() {
        // The real BulkExcel: it is a thread-local holder around POI with no collaborators, and
        // the export assertions are about what it writes into the sheet.
        this.service = new SourceJobBulkServiceImpl(this.transactionService, this.sourceJobRepository,
            this.schedulerRepository, new BulkExcel(), TestNotifications.recording(null, this.notificationCenterService, null));
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---- upload: a bad Task Id is a row error, not a dead request -------------------------------

    /** A one-row Job-Add sheet whose Task Id cell holds whatever is passed in. */
    private static FileUploadDto sheetWithTaskIdCell(String taskIdCell) throws Exception {
        return sheetWith(taskIdCell, "1");
    }

    /** The same sheet, with the Recurrence cell open to the test as well. */
    private static FileUploadDto sheetWith(String taskIdCell, String recurrenceCell) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(ProcessUtil.JOB_ADD);
            Row header = sheet.createRow(0);
            for (int i = 0; i < ProcessUtil.HEADER_FILED_BATCH_FILE.length; i++) {
                header.createCell(i).setCellValue(ProcessUtil.HEADER_FILED_BATCH_FILE[i]);
            }
            Row body = sheet.createRow(1);
            // Tomorrow, computed rather than written down: the validator refuses a start date in
            // the past, so a fixed one would pass today and fail for ever after.
            String startDate = LocalDate.now().plusDays(1).toString();
            String[] cells = { "nightly load", taskIdCell, startDate, "", "02:00",
                "Daily", recurrenceCell, "1", "False", "False", "False" };
            for (int i = 0; i < cells.length; i++) {
                body.createCell(i).setCellValue(cells[i]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            FileUploadDto fileUploadDto = new FileUploadDto();
            fileUploadDto.setFile(new MockMultipartFile("file", "jobs.xlsx",
                ProcessUtil.SHEET_NAME, out.toByteArray()));
            return fileUploadDto;
        }
    }

    @Test
    void aPastedCompositeTaskIdIsReportedAgainstItsRow() throws Exception {
        ResponseDto response = this.service.uploadSourceJob(sheetWithTaskIdCell("1043 [orders nightly]"));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        List<?> errors = (List<?>) response.getData();
        assertThat(errors).hasSize(1);
        // The row number is what makes the message usable -- row 2, one-based, as the sheet shows it.
        assertThat(String.valueOf(errors.get(0))).contains("row 2");
        assertThat(String.valueOf(errors.get(0))).contains("1043 [orders nightly]");
        verify(this.transactionService, never()).saveOrUpdateJob(any());
    }

    @Test
    void aTaskNameTypedIntoTheIdColumnIsReportedAgainstItsRow() throws Exception {
        ResponseDto response = this.service.uploadSourceJob(sheetWithTaskIdCell("orders nightly"));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(String.valueOf(((List<?>) response.getData()).get(0))).contains("row 2");
        verify(this.transactionService, never()).saveOrUpdateJob(any());
    }

    /** A numeric id that names no task still gets its own, different, row-numbered message. */
    @Test
    void anUnknownNumericTaskIdStillReportsTheDeletedTaskMessage() throws Exception {
        when(this.transactionService.findByTaskDetailIdAndTaskStatus(9999L))
            .thenReturn(Optional.empty());

        ResponseDto response = this.service.uploadSourceJob(sheetWithTaskIdCell("9999"));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(String.valueOf(((List<?>) response.getData()).get(0)))
            .contains("Deleted sourceTask");
    }

    /** A good sheet is still saved -- the parse guard must not have narrowed what is accepted. */
    @Test
    void aValidRowIsStillSaved() throws Exception {
        SourceTask linkedTask = new SourceTask();
        linkedTask.setTaskDetailId(1043L);
        linkedTask.setTenantId(TENANT_A);
        when(this.transactionService.findByTaskDetailIdAndTaskStatus(1043L))
            .thenReturn(Optional.of(linkedTask));

        ResponseDto response = this.service.uploadSourceJob(sheetWithTaskIdCell("1043"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.transactionService).saveOrUpdateJob(any(SourceJob.class));
        verify(this.transactionService).saveOrUpdateScheduler(any(Scheduler.class));
    }

    // ---- upload: a blank Recurrence is a rejected row, not a one-shot job -------------------------

    private void theLinkedTaskExists() {
        SourceTask linkedTask = new SourceTask();
        linkedTask.setTaskDetailId(1043L);
        linkedTask.setTenantId(TENANT_A);
        when(this.transactionService.findByTaskDetailIdAndTaskStatus(1043L))
            .thenReturn(Optional.of(linkedTask));
    }

    /**
     * JobDetailValidation only checks Recurrence when it has one -- "not null AND not one of the
     * allowed values" -- so an empty cell passed every check and the upload reported "Total N jobs
     * saved successfully." The Scheduler was written with interval_value NULL, which nothing
     * downstream can work with: the job was dispatched once at its start slot, and the first
     * updateNextScheduler after that asked computeNextRun for the following one, was told null
     * because there is no interval to step by, and marked the schedule expired for good.
     */
    @Test
    void aBlankRecurrenceIsReportedAgainstItsRowRatherThanSaved() throws Exception {
        this.theLinkedTaskExists();

        ResponseDto response = this.service.uploadSourceJob(sheetWith("1043", ""));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        List<?> errors = (List<?>) response.getData();
        assertThat(errors).hasSize(1);
        assertThat(String.valueOf(errors.get(0))).contains("Recurrence").contains("row 2");
        // The values this row's own frequency accepts, so the sheet is correctable in one pass
        // rather than by guessing -- the same courtesy the recurrence check itself extends.
        assertThat(String.valueOf(errors.get(0))).contains("[1, 2, 3, 4, 5, 6]");
        verify(this.transactionService, never()).saveOrUpdateJob(any());
        verify(this.transactionService, never()).saveOrUpdateScheduler(any());
    }

    /** A row that does carry a recurrence is saved with it, so nothing legitimate was narrowed. */
    @Test
    void aRowWithARecurrenceIsSavedWithThatInterval() throws Exception {
        this.theLinkedTaskExists();

        ResponseDto response = this.service.uploadSourceJob(sheetWith("1043", "2"));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<Scheduler> captor = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.transactionService).saveOrUpdateScheduler(captor.capture());
        assertThat(captor.getValue().getIntervalValue()).isEqualTo("2");
        // Seeded, so the job has a slot to be due at rather than being born with none.
        assertThat(captor.getValue().getNextRunAt()).isNotNull();
        // MIG-29: written with its job's tenant (the job takes the linked task's).
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
    }

    // ---- download: one query for the schedules, and no unguarded task dereference ----------------

    private static SourceJob exportableJob(long jobId, SourceTask taskDetail) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(jobId);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobName("job " + jobId);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(Execution.Auto);
        sourceJob.setPriority(1);
        sourceJob.setTaskDetail(taskDetail);
        return sourceJob;
    }

    private static SourceTask namedTask() {
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskDetailId(1043L);
        sourceTask.setTaskName("orders nightly");
        return sourceTask;
    }

    @Test
    void theExportReadsEverySchedulerInOneQuery() throws Exception {
        when(this.sourceJobRepository.findByTenantId(TENANT_A)).thenReturn(Arrays.asList(
            exportableJob(1L, namedTask()), exportableJob(2L, namedTask()), exportableJob(3L, namedTask())));
        when(this.schedulerRepository.findByJobIdIn(anyList())).thenReturn(Collections.emptyList());

        this.service.downloadListSourceJob();

        verify(this.schedulerRepository, times(1)).findByJobIdIn(anyList());
        // The per-row query is what made a 400-job export issue 400 extra round trips.
        verify(this.schedulerRepository, never()).findSchedulerByJobId(any());
    }

    @Test
    void aJobWithNoTaskDoesNotTakeTheWholeExportDown() throws Exception {
        when(this.sourceJobRepository.findByTenantId(TENANT_A)).thenReturn(Arrays.asList(
            exportableJob(1L, namedTask()), exportableJob(2L, null), exportableJob(3L, namedTask())));
        when(this.schedulerRepository.findByJobIdIn(anyList())).thenReturn(Collections.emptyList());

        // Previously a NullPointerException here, answered as HTTP 500, so nobody could export
        // anything at all until the offending row was found by hand.
        assertThatCode(() -> this.service.downloadListSourceJob()).doesNotThrowAnyException();
    }

}
