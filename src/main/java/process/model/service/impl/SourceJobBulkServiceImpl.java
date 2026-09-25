package process.model.service.impl;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.Scheduler;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobBulkService;
import process.security.JobOwnership;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import process.util.excel.BulkExcel;
import process.util.validation.JobDetailValidation;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;
import process.notifications.Notices;
import process.notifications.NotificationPort;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class SourceJobBulkServiceImpl implements SourceJobBulkService {

    private Logger logger = LoggerFactory.getLogger(SourceJobBulkServiceImpl.class);

    private final String SourceJob = "SourceJob";

    private final BulkExcel bulkExcel;
    private final TransactionServiceImpl transactionService;
    private final SourceJobRepository sourceJobRepository;
    private final SchedulerRepository schedulerRepository;
    private final NotificationPort notifications;

    public SourceJobBulkServiceImpl(TransactionServiceImpl transactionService,
        SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        BulkExcel bulkExcel,
        NotificationPort notifications) {
        this.transactionService = transactionService;
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.bulkExcel = bulkExcel;
        this.notifications = notifications;
    }

    private String[] getHEADER_FILED_BATCH_FILE() {
        return HEADER_FILED_BATCH_FILE;
    }

    private String[] getHEADER_FILED_BATCH_DOWNLOAD_FILE() { return HEADER_FILED_BATCH_DOWNLOAD_FILE; }

    @Override
    public ByteArrayOutputStream downloadSourceJobTemplateFile() throws Exception {

        try (InputStream templateStream = this.getClass().getClassLoader().getResourceAsStream(REAL_FILE_PATH)) {
            if (templateStream == null) {
                throw new IllegalStateException("Bundled job template resource not found: " + REAL_FILE_PATH);
            }
            try (XSSFWorkbook wb = new XSSFWorkbook(templateStream)) {
                XSSFSheet sheet = wb.getSheet(JOB_ADD);

                this.bulkExcel.fillDropDownValue(sheet,1,999,1, this.transactionService.findAllSourceTask().stream().map(String::valueOf).toArray(String[]::new));
                this.bulkExcel.fillDropDownValue(sheet,1,999,5, ProcessTimeUtil.frequency.toArray(new String[0]));

                this.bulkExcel.fillDropDownValue(sheet,1,999,7, ProcessTimeUtil.priority.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,999,8, ProcessTimeUtil.checked.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,999,9, ProcessTimeUtil.checked.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,999,10, ProcessTimeUtil.checked.toArray(new String[0]));
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                wb.write(byteArrayOutputStream);
                return byteArrayOutputStream;
            }
        }
    }

    @Override
    public ByteArrayOutputStream downloadListSourceJob() throws Exception {

        List<SourceJob> sourceJobs = (TenantContext.isPlatformAdmin()
            ? this.sourceJobRepository.findAll()
            : this.sourceJobRepository.findByTenantId(TenantContext.getTenantId())).stream()
            .filter(sourceJob -> sourceJob.getJobStatus() != Status.Delete)
            // A tenant user exports the jobs that name them, as their job list shows them (JobOwnership).
            .filter(JobOwnership::isVisibleToCaller)
            .collect(Collectors.toList());
        /*
         * One query for every schedule in the export, not one per row.
         *
         * findSchedulerByJobId sat inside the forEach below, so exporting a workspace of 400 jobs
         * issued 400 extra queries inside a single HTTP request -- while the jobs list screen does
         * the identical job with one findByJobIdIn. It also inherited the cardinality trap: that
         * derived query returns Optional, so a job that somehow carries two scheduler rows made
         * the whole export fail with IncorrectResultSizeDataAccessException. Reading them as a
         * list and keeping the first means one bad job costs its own schedule columns rather than
         * everybody's export.
         */
        // A lambda rather than SourceJob::getJobId: the sheet-name field above is also called
        // SourceJob and shadows the type, so the method reference resolves against a String.
        List<Long> jobIds = sourceJobs.stream().map(sourceJob -> sourceJob.getJobId()).collect(Collectors.toList());
        Map<Long, Scheduler> schedulerByJobId = jobIds.isEmpty() ? Collections.emptyMap()
            : this.schedulerRepository.findByJobIdIn(jobIds).stream()
                .collect(Collectors.toMap(Scheduler::getJobId, s -> s, (a, b) -> a));
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(SourceJob);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), this.getHEADER_FILED_BATCH_DOWNLOAD_FILE());
        sourceJobs.forEach(sourceJob -> {
            rowCount.getAndIncrement();
            List<String> dataCellValue = new ArrayList<>();
            dataCellValue.add(!ProcessUtil.isNull(sourceJob.getJobName()) ? String.valueOf(sourceJob.getJobName()) : "");
            // Guarded like every neighbouring cell. task_detail_id is nullable, and this was the
            // one cell that dereferenced straight through: a single legacy row without a task
            // aborted the entire export with a NullPointerException, which the controller
            // answered as HTTP 500, so nobody could export anything until that row was found.
            dataCellValue.add(!ProcessUtil.isNull(sourceJob.getTaskDetail())
                ? String.format("%d [%s]", sourceJob.getTaskDetail().getTaskDetailId(), sourceJob.getTaskDetail().getTaskName())
                : "");
            dataCellValue.add(String.valueOf(sourceJob.getExecution()));
            dataCellValue.add(String.valueOf(sourceJob.getPriority()));
            dataCellValue.add(String.valueOf(sourceJob.getJobStatus()));
            // Chicago wall-clock, as the Chicago JVM printed it; Timestamp#toString now prints the JVM's zone.
            dataCellValue.add(String.valueOf(BusinessTime.legacyText(sourceJob.getDateCreated())));

            Optional<Scheduler> scheduler = Optional.ofNullable(schedulerByJobId.get(sourceJob.getJobId()));
            if (scheduler.isPresent()) {
                dataCellValue.add(String.valueOf(scheduler.get().getStartDate()));
                dataCellValue.add(!ProcessUtil.isNull(scheduler.get().getEndDate()) ? String.valueOf(scheduler.get().getEndDate()): "");
                dataCellValue.add(!ProcessUtil.isNull(String.valueOf(scheduler.get().getStartTime())) ? String.valueOf(scheduler.get().getStartTime()) : "");
            } else {
                dataCellValue.add("");
                dataCellValue.add("");
                dataCellValue.add("");
            }
            dataCellValue.add(!ProcessUtil.isNull(sourceJob.getLastJobRun()) ? String.valueOf(sourceJob.getLastJobRun()) : "");
            if (scheduler.isPresent()) {
                dataCellValue.add(!ProcessUtil.isNull(scheduler.get().getNextRunAt()) ? String.valueOf(scheduler.get().getNextRunAt()) : "");
            } else {
                dataCellValue.add("");
            }
            dataCellValue.add(!ProcessUtil.isNull(sourceJob.getJobRunningStatus()) ? String.valueOf(sourceJob.getJobRunningStatus()) : "");
            dataCellValue.add(sourceJob.isCompleteJob() ? ProcessTimeUtil.checked.get(0): ProcessTimeUtil.checked.get(1));
            dataCellValue.add(sourceJob.isFailJob() ? ProcessTimeUtil.checked.get(0) : ProcessTimeUtil.checked.get(1));
            dataCellValue.add(sourceJob.isSkipJob() ? ProcessTimeUtil.checked.get(0) : ProcessTimeUtil.checked.get(1));
            this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
        }
    }

    public ResponseDto uploadSourceJob(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploading file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream())) {
        if (ProcessUtil.isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new ResponseDto(ERROR,  "You uploaded an empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(JOB_ADD);
        if(ProcessUtil.isNull(sheet)) {
            return new ResponseDto(ERROR, "Sheet not found with (Job-Add)");
        } else if (sheet.getLastRowNum() < 1) {
            return new ResponseDto(ERROR,  "You cannot upload an empty file.");
        } else if(sheet.getLastRowNum() > 1001) {
            return new ResponseDto(ERROR,"File support 1000 rows at a time.");
        }
        List<JobDetailValidation> jobDetailValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Row currentRow : sheet) {

            if (currentRow.getRowNum() == 0) {

                for (int i = 0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.getHEADER_FILED_BATCH_FILE()[i])) {
                        return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1) + " "
                            + this.getHEADER_FILED_BATCH_FILE()[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {

                JobDetailValidation jobDetailValidation = new JobDetailValidation();
                jobDetailValidation.setRowCounter(currentRow.getRowNum() + 1);

                for (int i = 0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                    if (i == 0) {
                        jobDetailValidation.setJobName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 1) {
                        jobDetailValidation.setTaskId(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 2) {
                        jobDetailValidation.setStartDate(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 3) {
                        jobDetailValidation.setEndDate(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 4) {
                        jobDetailValidation.setStartTime(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 5) {
                        jobDetailValidation.setFrequency(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 6) {
                        jobDetailValidation.setRecurrence(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 7) {
                        jobDetailValidation.setPriority(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 8) {
                        jobDetailValidation.setEmailJobComplete(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 9) {
                        jobDetailValidation.setEmailJobFail(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == 10) {
                        jobDetailValidation.setEmailJobSkip(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                jobDetailValidation.isValidJobDetail();
                if (!ProcessUtil.isNull(jobDetailValidation.getTaskId())) {
                    /*
                     * Parsed rather than trusted. Long.valueOf on the raw cell threw
                     * NumberFormatException straight out of uploadSourceJob, which the controller
                     * turned into HTTP 400 "Sorry, the file could not be uploaded. Please contact
                     * support." -- no row number, no column, and no sign that the other ninety-nine
                     * rows were fine. The cell is free text from the sheet, and the two easiest
                     * things to put in it are both unparsable: the task's name, and the composite
                     * "1043 [orders nightly]" that the job list download writes into this very
                     * column. Now it joins the per-row error list like every other bad cell.
                     */
                    Long taskId = ProcessUtil.parseLongOrNull(jobDetailValidation.getTaskId());
                    if (taskId == null) {
                        jobDetailValidation.setErrorMsg("Task Id must be the numeric id at row "
                            + (currentRow.getRowNum() + 1) + "; got \"" + jobDetailValidation.getTaskId() + "\".\n");
                    } else if (!this.transactionService.findByTaskDetailIdAndTaskStatus(taskId).isPresent()) {
                        jobDetailValidation.setErrorMsg("Task " + taskId + " at row " + (currentRow.getRowNum() + 1)
                            + " is not an active task in this workspace.");
                    }
                }
                /*
                 * A blank Recurrence cell is a rejected row, not a job.
                 *
                 * JobDetailValidation only checks Recurrence when it has one -- "not null AND not
                 * one of the allowed values" -- so an empty cell passed every check and produced a
                 * Scheduler with no interval_value. Nothing downstream can work with that:
                 * applyInitialSchedule seeds next_run_at from the start date and stops there, and
                 * the first time the engine dispatches the job updateNextScheduler asks
                 * computeNextRun for the following slot, is told null because there is no interval
                 * to step by, and marks the schedule expired. The job ran exactly once and was
                 * then dead for good, while the upload reported "Total N jobs saved successfully."
                 * and the job list showed it Active with no schedule against it.
                 *
                 * Refused rather than defaulted: the sheet gives no way to tell which cadence was
                 * meant, and a job silently given one it was not asked for is the worse outcome of
                 * the two -- the row is in front of the person who can fix it. The message names
                 * the values this row's own frequency accepts, the way the recurrence check does.
                 */
                if (ProcessUtil.isNull(jobDetailValidation.getRecurrence())) {
                    List<?> allowed = ProcessTimeUtil.frequencyDetail.get(jobDetailValidation.getFrequency());
                    jobDetailValidation.setErrorMsg("Recurrence should not be empty at row "
                        + (currentRow.getRowNum() + 1) + "; a schedule with no recurrence runs once and then expires"
                        + (allowed != null ? ". It should be " + allowed + "." : ".") + "\n");
                }
                if (!ProcessUtil.isNull(jobDetailValidation.getErrorMsg())) {
                    errors.add(jobDetailValidation.getErrorMsg());
                    continue;
                }
                jobDetailValidations.add(jobDetailValidation);
            }
        }
        if (!errors.isEmpty()) {
            return new ResponseDto(ERROR, ProcessUtil.rejectedRowsMessage(errors.size()), errors);
        }
        for (JobDetailValidation jobDetailValidation : jobDetailValidations) {
            SourceJob sourceJob = new SourceJob();
            sourceJob.setJobName(jobDetailValidation.getJobName());
            SourceTask linkedTask = this.transactionService.findByTaskDetailIdAndTaskStatus(Long.valueOf(jobDetailValidation.getTaskId())).get();
            sourceJob.setTaskDetail(linkedTask);
            sourceJob.setJobStatus(Status.Active);

            sourceJob.setTenantId(linkedTask.getTenantId());
            sourceJob.setAssignedUserId(TenantContext.getAppUserId());
            // The importer is the assignee, and their username is the token's subject (MIG-107).
            sourceJob.setAssignedUsername(TenantContext.getUsername());
            sourceJob.setPriority(Integer.valueOf(jobDetailValidation.getPriority()));
            sourceJob.setSkipJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobSkip()));
            sourceJob.setFailJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobFail()));
            sourceJob.setCompleteJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobComplete()));
            sourceJob.setExecution(Execution.Auto);
            this.transactionService.saveOrUpdateJob(sourceJob);
            Scheduler scheduler = new Scheduler();
            scheduler.setTenantId(sourceJob.getTenantId());
            scheduler.setStartDate(LocalDate.parse(jobDetailValidation.getStartDate()));
            if (!StringUtils.isEmpty(jobDetailValidation.getEndDate())) {
                scheduler.setEndDate(LocalDate.parse(jobDetailValidation.getEndDate()));
            }
            scheduler.setStartTime(LocalTime.parse(jobDetailValidation.getStartTime()));
            scheduler.setFrequency(jobDetailValidation.getFrequency());
            // Unguarded: a row with no recurrence never reaches this loop any more, and a guard
            // that cannot be false is what hid the one-shot schedule in the first place.
            scheduler.setIntervalValue(jobDetailValidation.getRecurrence());
            ProcessTimeUtil.applyInitialSchedule(scheduler);
            scheduler.setJobId(sourceJob.getJobId());
            this.transactionService.saveOrUpdateScheduler(scheduler);
        }
        this.notifications.notificationCreated(TenantContext.getTenantId(), Notices.notice(TenantContext.getAppUserId(), NotificationType.BATCH_DONE, NotificationSeverity.INFO, "Batch upload finished", String.format("Total %d jobs saved successfully.", jobDetailValidations.size()), "/jobList"));
        return new ResponseDto(SUCCESS, String.format("Total %d jobs saved successfully.", jobDetailValidations.size()));
        }
    }

}