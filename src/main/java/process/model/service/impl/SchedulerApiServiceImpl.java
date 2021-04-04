package process.model.service.impl;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import process.model.dto.*;
import process.model.enums.Status;
import process.model.service.SchedulerApiService;
import process.util.ProcessTimeUtil;
import process.util.excel.BulkExcel;
import process.util.exception.ExceptionUtil;
import process.util.validation.JobDetailValidation;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Service
@Scope("singleton")
public class SchedulerApiServiceImpl implements SchedulerApiService {

    private Logger logger = LoggerFactory.getLogger(SchedulerApiServiceImpl.class);

    // env-filed
    @Value("${storage.efsFileDire}")
    private String tempFileStoreDirectory;

    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * The method used to download the template file for batch scheduler
     * @return ByteArrayInputStream
     */
    @Override
    public ByteArrayInputStream downloadBatchSchedulerTemplateFile() throws Exception {
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
        // 2nd insert data to newly copied file. So that template coludn't be changed.
        XSSFWorkbook wb = new XSSFWorkbook(new File(fileUploadPath));
        // this will create the sheet and hide the sheet
        this.bulkExcel.createExcelDependentDataValidationListsUsingNamedRanges(ProcessTimeUtil.frequencyDetail, wb);
        XSSFSheet sheet = wb.getSheet(JOB_ADD);
        this.bulkExcel.fillDropDownValue(sheet,1,1, ProcessTimeUtil.triggerDetail.stream().toArray(String[]::new));
        this.bulkExcel.fillDropDownValue(sheet,1,5, ProcessTimeUtil.frequency.stream().toArray(String[]::new));
        //this.fillDropDownValueV2(sheet,1,5, CATEGORIES);
        //this.fillDropDownValueV2(sheet,1,6, FORMULATE);
        wb.write(fileOut);
        fileOut.close();
        wb.close();
        // read the file
        File file = new File(fileUploadPath);
        ByteArrayInputStream byteArrayOutputStream = new ByteArrayInputStream(FileUtils.readFileToByteArray(file));
        // delete the file
        file.delete();
        return byteArrayOutputStream;
    }

    /**
     * The method use to process the batch file and upload the data into database
     * max file have 1000 row if file have more then 1000 row its will reject the process
     * @param object
     * @return ResponseDto
     */
    public ResponseDto uploadJobFile(FileUploadDto object) throws Exception {
        logger.info("### Start bulk uploading file!");
        if (!object.getFile().getContentType().equalsIgnoreCase(SHEET_NAME)) {
            logger.info("File Type " + object.getFile().getContentType());
            return new ResponseDto(ERROR, "You can upload only .xlsx extension file.");
        } else {
            // fill the stream of file into work-book
            XSSFWorkbook workbook = new XSSFWorkbook(object.getFile().getInputStream());
            if (workbook == null || workbook.getNumberOfSheets() == 0) {
                return new ResponseDto(ERROR,  "You uploaded empty file.");
            } else {
                XSSFSheet sheet = workbook.getSheet(JOB_ADD);
                if(sheet != null) {
                    if (sheet.getLastRowNum() < 1) {
                        return new ResponseDto(ERROR,  "You can't upload empty file.");
                    } else if(sheet.getLastRowNum() > 1001) {
                        return new ResponseDto(ERROR,"File support 1000 rows at a time.");
                    } else {
                        List<JobDetailValidation> jobDetailValidations = new ArrayList<>();
                        Iterator<Row> rows = sheet.iterator();
                        while (rows.hasNext()) {
                            Row currentRow = rows.next();
                            // header validation check
                            if (currentRow.getRowNum() == 0) {
                                if (currentRow.getPhysicalNumberOfCells() != 7) {
                                    return new ResponseDto(ERROR, "File at row " + (currentRow.getRowNum() + 1) + " heading missing.");
                                } else {
                                    // loop on the header
                                    for (int i=0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                                        if (!currentRow.getCell(i).getStringCellValue().equals(this.getHEADER_FILED_BATCH_FILE()[i])) {
                                            return new ResponseDto(ERROR,"File at row " +
                                                (currentRow.getRowNum() + 1) + " " + this.getHEADER_FILED_BATCH_FILE()[i] + " heading missing.");
                                        }
                                    }
                                }
                            } else if (currentRow.getRowNum() > 0) {
                                // data validation and save
                                JobDetailValidation jobDetailValidation = new JobDetailValidation();
                                // get the row data and add into job-dto
                                for (int i=0; i < this.getHEADER_FILED_BATCH_FILE().length; i++) {
                                    if (i==0) {
                                        jobDetailValidation.setJobName(this.bulkExcel.getCellDetail(currentRow, i));
                                    } else if (i==1) {
                                        jobDetailValidation.setTriggerDetail(this.bulkExcel.getCellDetail(currentRow, i));
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
                                // job name already exist
                                if (this.transactionService.findByJobNameAndJobStatus(
                                    jobDetailValidation.getJobName(), Status.Active).isPresent()) {
                                    return new ResponseDto(ERROR,  "JobName already exist at row " + (currentRow.getRowNum() + 1) + ".");
                                } else if (!jobDetailValidation.isValidJobDetail()) {
                                    return new ResponseDto(ERROR, String.format(jobDetailValidation.getErrorMsg(), (currentRow.getRowNum() + 1)));
                                } else {
                                    jobDetailValidations.add(jobDetailValidation);
                                }
                            }
                        }
                        // convert the data into real data and save
                        if (jobDetailValidations.size() > 0) {
                            jobDetailValidations.forEach(jobDetailValidation -> {
                                try {
                                    // save the job and scheduler
                                    JobDto jobDto = new JobDto();
                                    jobDto.setJobName(jobDetailValidation.getJobName());
                                    jobDto.setTriggerDetail(jobDetailValidation.getTriggerDetail());
                                    jobDto.setJobStatus(Status.Active);
                                    this.transactionService.saveJob(jobDto);
                                    // ------------------
                                    SchedulerDto schedulerDto = new SchedulerDto();
                                    schedulerDto.setStartDate(LocalDate.parse(jobDetailValidation.getStartDate()));
                                    if (!StringUtils.isEmpty(jobDetailValidation.getEndDate())) {
                                        schedulerDto.setEndDate(LocalDate.parse(jobDetailValidation.getEndDate()));
                                    }
                                    schedulerDto.setStartTime(LocalTime.parse(jobDetailValidation.getStartTime()));
                                    schedulerDto.setFrequency(jobDetailValidation.getFrequency());
                                    if (!StringUtils.isEmpty(jobDetailValidation.getRecurrence())) {
                                        schedulerDto.setRecurrence(jobDetailValidation.getRecurrence());
                                    }
                                    schedulerDto.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                                        LocalDate.parse(jobDetailValidation.getStartDate()), jobDetailValidation.getStartTime()));
                                    schedulerDto.setJobId(jobDto.getJobId());
                                    this.transactionService.saveScheduler(schedulerDto);
                                } catch (Exception ex) {
                                    logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
                                }
                            });
                        }
                        return new ResponseDto(SUCCESS, String.format("Total %d Job Save Successfully", jobDetailValidations.size()));
                    }
                } else {
                    return new ResponseDto(ERROR, "Sheet not found with (Job-Add)");
                }
            }
        }
    }

    private String[] getHEADER_FILED_BATCH_FILE() {
        return HEADER_FILED_BATCH_FILE;
    }
}
