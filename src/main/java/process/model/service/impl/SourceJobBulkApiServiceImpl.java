package process.model.service.impl;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.Scheduler;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobBulkApiService;
import process.util.ProcessTimeUtil;
import process.util.excel.BulkExcel;
import process.util.validation.JobDetailValidation;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static process.util.ProcessUtil.*;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceJobBulkApiServiceImpl implements SourceJobBulkApiService {

    private Logger logger = LoggerFactory.getLogger(SourceJobBulkApiServiceImpl.class);

    // env-filed
    @Value("${storage.efsFileDire}")
    private String tempFileStoreDirectory;
    private final String SourceJob = "SourceJob";
    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;

    private String[] getHEADER_FILED_BATCH_FILE() {
        return HEADER_FILED_BATCH_FILE;
    }

    private String[] getHEADER_FILED_BATCH_DOWNLOAD_FILE() { return HEADER_FILED_BATCH_DOWNLOAD_FILE; }

    /**
     * The method used to download the template file for batch scheduler
     * @return ByteArrayInputStream downloadSourceJobBatchSchedulerTemplateFile
     */
    @Override
    public ByteArrayOutputStream downloadSourceJobTemplateFile() throws Exception {
        // template_url
        String basePath = this.tempFileStoreDirectory + File.separator;
        // read the template
        ClassLoader cl = this.getClass().getClassLoader();
        InputStream inputStream = cl.getResourceAsStream(REAL_FILE_PATH);
        // temp file path
        String fileUploadPath = basePath + System.currentTimeMillis() + XLSX_EXTENSION;
        // 1st copy template.
        FileOutputStream fileOut = new FileOutputStream(fileUploadPath);
        IOUtils.copy(inputStream, fileOut);
        // after copy the stream into file close
        if (inputStream != null) {
            inputStream.close();
        }
        // 2nd insert data to newly copied file. So that template couldn't be changed.
        XSSFWorkbook wb = new XSSFWorkbook(new File(fileUploadPath));
        XSSFSheet sheet = wb.getSheet(JOB_ADD);
        /**Trigger Detail fetch from db as per user login*/
        this.bulkExcel.fillDropDownValue(sheet,1,1, this.transactionService.findAllSourceTask()
            .stream().map(taskId -> String.valueOf(taskId)).toArray(String[]::new));
        this.bulkExcel.fillDropDownValue(sheet,1,5, ProcessTimeUtil.frequency.stream().toArray(String[]::new));
        wb.write(fileOut);
        fileOut.close();
        wb.close();
        // read the file
        File file = new File(fileUploadPath);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(FileUtils.readFileToByteArray(file));
        // delete the file
        file.delete();
        return byteArrayOutputStream;
    }

    /**
     * The method used to download the template file for batch scheduler
     * @return ByteArrayInputStream downloadListSourceJobBatchScheduler
     */
    @Override
    public ByteArrayOutputStream downloadListSourceJob() throws Exception {
        List<SourceJob> sourceJobs = this.sourceJobRepository.findAll();
        XSSFWorkbook workbook = new XSSFWorkbook();
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(SourceJob);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), this.getHEADER_FILED_BATCH_DOWNLOAD_FILE());
        sourceJobs.forEach(sourceJob -> {
            rowCount.getAndIncrement();
            List<String> dataCellValue = new ArrayList<>();
            dataCellValue.add(!isNull(sourceJob.getJobName()) ? String.valueOf(sourceJob.getJobName()) : "");
            dataCellValue.add(String.format("%d [%s]", sourceJob.getTaskDetail().getTaskDetailId(), sourceJob.getTaskDetail().getTaskName()));
            dataCellValue.add(String.valueOf(sourceJob.getExecution()));
            dataCellValue.add(String.valueOf(sourceJob.getJobStatus()));
            dataCellValue.add(String.valueOf(sourceJob.getDateCreated()));
            // check the scheduler
            Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJob.getJobId());
            if (scheduler.isPresent()) {
                dataCellValue.add(String.valueOf(scheduler.get().getStartDate()));
                dataCellValue.add(!isNull(scheduler.get().getEndDate()) ? String.valueOf(scheduler.get().getEndDate()): "");
                dataCellValue.add(!isNull(String.valueOf(scheduler.get().getStartTime())) ? String.valueOf(scheduler.get().getStartTime()) : "");
            } else {
                dataCellValue.add("");
                dataCellValue.add("");
                dataCellValue.add("");
            }
            dataCellValue.add(!isNull(sourceJob.getLastJobRun()) ? String.valueOf(sourceJob.getLastJobRun()) : "");
            if (scheduler.isPresent()) {
                dataCellValue.add(!isNull(scheduler.get().getRecurrenceTime()) ? String.valueOf(scheduler.get().getRecurrenceTime()) : "");
            } else {
                dataCellValue.add("");
            }
            dataCellValue.add(!isNull(sourceJob.getJobRunningStatus()) ? String.valueOf(sourceJob.getJobRunningStatus()) : "");
            dataCellValue.add(sourceJob.isCompleteJob() ? "Yes": "No");
            dataCellValue.add(sourceJob.isFailJob() ? "Yes" : "No");
            dataCellValue.add(sourceJob.isSkipJob() ? "Yes" : "No");
            this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    /**
     * The method use to process the batch file and upload the data into database
     * max file have 1000 row if file have more than 1000 row it's will reject the process
     * @param object
     * @return ResponseDto uploadSourceJob
     */
    public ResponseDto uploadSourceJob(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploading file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        }
        // fill the stream with file into work-book
        XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream());
        if (workbook == null || workbook.getNumberOfSheets() == 0) {
            return new ResponseDto(ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(JOB_ADD);
        if(isNull(sheet)) {
            return new ResponseDto(ERROR, "Sheet not found with (Job-Add)");
        } else if (sheet.getLastRowNum() < 1) {
            return new ResponseDto(ERROR,  "You can't upload empty file.");
        } else if(sheet.getLastRowNum() > 1001) {
            return new ResponseDto(ERROR,"File support 1000 rows at a time.");
        }
        List<JobDetailValidation> jobDetailValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            // header validation check
            if (currentRow.getRowNum() == 0) {
                // loop on the header
                for (int i=0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.getHEADER_FILED_BATCH_FILE()[i])) {
                        return new ResponseDto(ERROR,"File at row " + (currentRow.getRowNum() + 1) + " "
                            + this.getHEADER_FILED_BATCH_FILE()[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                // data validation and save
                JobDetailValidation jobDetailValidation = new JobDetailValidation();
                jobDetailValidation.setRowCounter(currentRow.getRowNum()+1);
                // get the row data and add into job-dto
                for (int i=0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                    if (i==0) {
                        jobDetailValidation.setJobName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==1) {
                        jobDetailValidation.setTaskId(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==2) {
                        jobDetailValidation.setStartDate(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==3) {
                        jobDetailValidation.setEndDate(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==4) {
                        jobDetailValidation.setStartTime(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==5) {
                        jobDetailValidation.setFrequency(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==6) {
                        jobDetailValidation.setRecurrence(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                jobDetailValidation.isValidJobDetail();
                if (!isNull(jobDetailValidation.getTaskId())) {
                    if (!this.transactionService.findByTaskDetailIdAndTaskStatus(
                        Long.valueOf(jobDetailValidation.getTaskId())).isPresent()) {
                        jobDetailValidation.setErrorMsg("Delete sourceTask not link with source job at row " + (currentRow.getRowNum() + 1) + ".\n");
                    }
                }
                if (!isNull(jobDetailValidation.getErrorMsg())) {
                    errors.add(jobDetailValidation.getErrorMsg());
                    continue;
                }
                jobDetailValidations.add(jobDetailValidation);
            }
        }
        if (errors.size() > 0) {
            return new ResponseDto(ERROR, String.format("Total %d source jobs invalid.", errors.size()), errors);
        }
        jobDetailValidations.forEach(jobDetailValidation -> {
            // save the job and scheduler
            SourceJob sourceJob = new SourceJob();
            sourceJob.setJobName(jobDetailValidation.getJobName());
            sourceJob.setTaskDetail(this.transactionService.findByTaskDetailIdAndTaskStatus(
                Long.valueOf(jobDetailValidation.getTaskId())).get());
            sourceJob.setJobStatus(Status.Active);
            sourceJob.setPriority(5);
            sourceJob.setSkipJob(true);
            sourceJob.setFailJob(true);
            sourceJob.setCompleteJob(true);
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
            scheduler.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                LocalDate.parse(jobDetailValidation.getStartDate()), jobDetailValidation.getStartTime()));
            scheduler.setJobId(sourceJob.getJobId());
            this.transactionService.saveOrUpdateScheduler(scheduler);
        });
        return new ResponseDto(SUCCESS, String.format("Total %d Job Save Successfully", jobDetailValidations.size()));
    }

}