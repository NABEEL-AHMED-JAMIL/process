package process.model.service.impl;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.Scheduler;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobBulkService;
import process.security.TenantContext;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import process.util.excel.BulkExcel;
import process.util.validation.JobDetailValidation;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceJobBulkServiceImpl implements SourceJobBulkService {

    private Logger logger = LoggerFactory.getLogger(SourceJobBulkServiceImpl.class);

    private final String SourceJob = "SourceJob";

    private final BulkExcel bulkExcel;
    private final TransactionServiceImpl transactionService;
    private final SourceJobRepository sourceJobRepository;
    private final SchedulerRepository schedulerRepository;

    public SourceJobBulkServiceImpl(TransactionServiceImpl transactionService,
        SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        BulkExcel bulkExcel) {
        this.transactionService = transactionService;
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.bulkExcel = bulkExcel;
    }

    private String[] getHEADER_FILED_BATCH_FILE() {
        return HEADER_FILED_BATCH_FILE;
    }

    private String[] getHEADER_FILED_BATCH_DOWNLOAD_FILE() { return HEADER_FILED_BATCH_DOWNLOAD_FILE; }

    /**
     * The method used to download the template file for batch scheduler
     * @return ByteArrayInputStream
     */
    @Override
    public ByteArrayOutputStream downloadSourceJobTemplateFile() throws Exception {
        // Used to copy the bundled template out to a temp file, then open THAT file for read
        // (XSSFWorkbook) and, in the very same try-with-resources, open a second stream to the
        // SAME path for write (FileOutputStream) -- opening a FileOutputStream truncates its
        // target immediately, so the on-disk file was being zeroed out while the just-opened
        // XSSFWorkbook still had unread parts to lazily pull from it, corrupting the zip (POI
        // would blow up later with an EOFException while parsing docProps/app.xml). Reading the
        // bundled resource straight into an in-memory XSSFWorkbook and writing straight back out
        // to a ByteArrayOutputStream -- same pattern every other download method in this class
        // already uses -- sidesteps the whole read/write-same-file hazard, with no temp file or
        // cleanup needed at all.
        try (InputStream templateStream = this.getClass().getClassLoader().getResourceAsStream(REAL_FILE_PATH)) {
            if (templateStream == null) {
                throw new IllegalStateException("Bundled job template resource not found: " + REAL_FILE_PATH);
            }
            try (XSSFWorkbook wb = new XSSFWorkbook(templateStream)) {
                XSSFSheet sheet = wb.getSheet(JOB_ADD);
                /**Trigger Detail fetch from db as per user login*/
                this.bulkExcel.fillDropDownValue(sheet,1,1, this.transactionService.findAllSourceTask().stream().map(String::valueOf).toArray(String[]::new));
                this.bulkExcel.fillDropDownValue(sheet,1,5, ProcessTimeUtil.frequency.toArray(new String[0]));
                // Priority
                this.bulkExcel.fillDropDownValue(sheet,1,7, ProcessTimeUtil.priority.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,8, ProcessTimeUtil.checked.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,9, ProcessTimeUtil.checked.toArray(new String[0]));
                this.bulkExcel.fillDropDownValue(sheet,1,10, ProcessTimeUtil.checked.toArray(new String[0]));
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                wb.write(byteArrayOutputStream);
                return byteArrayOutputStream;
            }
        }
    }

    /**
     * The method used to download the template file for batch scheduler
     * @return ByteArrayInputStream
     */
    @Override
    public ByteArrayOutputStream downloadListSourceJob() throws Exception {
        // findAll() has no tenant scoping of its own -- unfiltered, this exported every tenant's
        // jobs into whichever tenant admin's Excel download, not just the caller's own.
        // findByTenantId scopes it at the query level instead of pulling every tenant's rows
        // into memory just to filter/discard most of them.
        List<SourceJob> sourceJobs = TenantContext.isPlatformAdmin()
            ? this.sourceJobRepository.findAll()
            : this.sourceJobRepository.findByTenantId(TenantContext.getTenantId());
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
            dataCellValue.add(String.format("%d [%s]", sourceJob.getTaskDetail().getTaskDetailId(), sourceJob.getTaskDetail().getTaskName()));
            dataCellValue.add(String.valueOf(sourceJob.getExecution()));
            dataCellValue.add(String.valueOf(sourceJob.getPriority()));
            dataCellValue.add(String.valueOf(sourceJob.getJobStatus()));
            dataCellValue.add(String.valueOf(sourceJob.getDateCreated()));
            // check the scheduler
            Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJob.getJobId());
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
                dataCellValue.add(!ProcessUtil.isNull(scheduler.get().getRecurrenceTime()) ? String.valueOf(scheduler.get().getRecurrenceTime()) : "");
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

    /**
     * The method use to process the batch file and upload the data into database
     * max file have 1000 row if file have more than 1000 row it's will reject the process
     * @param object
     * @return ResponseDto
     */
    public ResponseDto uploadSourceJob(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploading file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        }
        // fill the stream with file into work-book
        try (XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream())) {
        if (ProcessUtil.isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new ResponseDto(ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(JOB_ADD);
        if(ProcessUtil.isNull(sheet)) {
            return new ResponseDto(ERROR, "Sheet not found with (Job-Add)");
        } else if (sheet.getLastRowNum() < 1) {
            return new ResponseDto(ERROR,  "You can't upload empty file.");
        } else if(sheet.getLastRowNum() > 1001) {
            return new ResponseDto(ERROR,"File support 1000 rows at a time.");
        }
        List<JobDetailValidation> jobDetailValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Row currentRow : sheet) {
            // header validation check
            if (currentRow.getRowNum() == 0) {
                // loop on the header
                for (int i = 0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.getHEADER_FILED_BATCH_FILE()[i])) {
                        return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1) + " "
                            + this.getHEADER_FILED_BATCH_FILE()[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                // data validation and save
                JobDetailValidation jobDetailValidation = new JobDetailValidation();
                jobDetailValidation.setRowCounter(currentRow.getRowNum() + 1);
                // get the row data and add into job-dto
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
                    if (!this.transactionService.findByTaskDetailIdAndTaskStatus(Long.valueOf(jobDetailValidation.getTaskId())).isPresent()) {
                        jobDetailValidation.setErrorMsg("Delete sourceTask not link with source job at row " + (currentRow.getRowNum() + 1) + ".\n");
                    }
                }
                if (!ProcessUtil.isNull(jobDetailValidation.getErrorMsg())) {
                    errors.add(jobDetailValidation.getErrorMsg());
                    continue;
                }
                jobDetailValidations.add(jobDetailValidation);
            }
        }
        if (!errors.isEmpty()) {
            return new ResponseDto(ERROR, String.format("Total %d source jobs invalid.", errors.size()), errors);
        }
        for (JobDetailValidation jobDetailValidation : jobDetailValidations) {// save the job and scheduler
            SourceJob sourceJob = new SourceJob();
            sourceJob.setJobName(jobDetailValidation.getJobName());
            sourceJob.setTaskDetail(this.transactionService.findByTaskDetailIdAndTaskStatus(Long.valueOf(jobDetailValidation.getTaskId())).get());
            sourceJob.setJobStatus(Status.Active);
            // These two were missing entirely -- a bulk-uploaded job silently had no tenant
            // (invisible to its own tenant's scoped queries, see TenantFilterHelper) and no
            // assignee (its run status never gets pushed to anyone, see
            // BulkAction.sendJobStatusNotification). Matches SourceJobServiceImpl.addSourceJob.
            sourceJob.setTenantId(TenantContext.getTenantId());
            sourceJob.setAssignedUserId(TenantContext.getAppUserId());
            sourceJob.setPriority(Integer.valueOf(jobDetailValidation.getPriority()));
            sourceJob.setSkipJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobSkip()));
            sourceJob.setFailJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobFail()));
            sourceJob.setCompleteJob(Boolean.parseBoolean(jobDetailValidation.getEmailJobComplete()));
            sourceJob.setExecution(Execution.Auto);
            this.transactionService.saveOrUpdateJob(sourceJob);
            Scheduler scheduler = new Scheduler();
            scheduler.setStartDate(LocalDate.parse(jobDetailValidation.getStartDate()));
            if (!StringUtils.isEmpty(jobDetailValidation.getEndDate())) {
                scheduler.setEndDate(LocalDate.parse(jobDetailValidation.getEndDate()));
            }
            scheduler.setStartTime(LocalTime.parse(jobDetailValidation.getStartTime()));
            scheduler.setFrequency(jobDetailValidation.getFrequency());
            if (!StringUtils.isEmpty(jobDetailValidation.getRecurrence())) {
                scheduler.setRecurrence(jobDetailValidation.getRecurrence());
            }
            scheduler.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(LocalDate.parse(jobDetailValidation.getStartDate()), jobDetailValidation.getStartTime()));
            scheduler.setJobId(sourceJob.getJobId());
            this.transactionService.saveOrUpdateScheduler(scheduler);
        }
        return new ResponseDto(SUCCESS, String.format("Total %d Job Save Successfully", jobDetailValidations.size()));
        }
    }

}