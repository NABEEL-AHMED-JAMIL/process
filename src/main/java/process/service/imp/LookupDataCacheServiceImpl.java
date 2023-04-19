package process.service.imp;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.AppUser;
import process.model.pojo.LookupData;
import process.model.repository.AppUserRepository;
import process.model.repository.LookupDataRepository;
import process.payload.request.FileUploadRequest;
import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.payload.response.LookupDataResponse;
import process.service.LookupDataCacheService;
import process.util.ProcessUtil;
import process.util.excel.BulkExcel;
import process.util.lookuputil.LookupDetailUtil;
import process.util.lookuputil.Status;
import process.util.validation.LookupValidation;
import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;


/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class LookupDataCacheServiceImpl implements LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String SUB_LOOKUP_DATA = "subLookupData";

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock writeLock = readWriteLock.writeLock();

    @Value("${storage.efsFileDire}")
    private String tempStoreDirectory;
    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    private Map<String, LookupDataResponse> lookupCacheMap = new HashMap<>();

    /**
     * Method use to cache the data
     * */
    @PostConstruct
    public void initialize() {
        this.writeLock.lock();
        try {
            logger.info("****************Cache-Lookup-Start***************************");
            this.lookupCacheMap = new HashMap<>();
            Iterable<LookupData> lookupDataList = this.lookupDataRepository.findByParentLookupIsNull();
            lookupDataList.forEach(lookupData -> {
                if (this.lookupCacheMap.containsKey(lookupData.getLookupType())) {
                    this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
                } else {
                    this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
                }
            });
            logger.info("***************Cache-Lookup-End********************************");
        } finally {
            this.writeLock.unlock();
        }
    }

    /**
     * Method use to add new filed into cache
     * */
    private void addNewLookupData(LookupData lookupData) {
        this.writeLock.lock();
        try {
            this.lookupCacheMap.put(lookupData.getLookupType(),
                getLookupDataDetail(lookupData));
        } finally {
            this.writeLock.unlock();
        }
    }

    /**
     * Method use to add new filed into db & cache
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request addLookupData :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupValue missing.");
        } else if (isNull(lookupDataRequest.getLookupType())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupType missing.");
        } else if (isNull(lookupDataRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (this.lookupDataRepository.findByLookupType(lookupDataRequest.getLookupType()).isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "LookupType already exist.");
        }
        LookupData lookupData = new LookupData();
        lookupData.setLookupValue(lookupDataRequest.getLookupValue());
        lookupData.setLookupType(lookupDataRequest.getLookupType());
        if (!isNull(lookupDataRequest.getDescription())) {
            lookupData.setDescription(lookupDataRequest.getDescription());
        }
        if (!isNull(lookupDataRequest.getParentLookupId())) {
            Optional<LookupData> parentLookupData = this.lookupDataRepository
                .findById(lookupDataRequest.getParentLookupId());
            if (parentLookupData.isPresent()) {
                lookupData.setParentLookup(parentLookupData.get());
            }
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        lookupData.setAppUser(appUser.get());
        this.addNewLookupData(this.lookupDataRepository.save(lookupData));
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "LookupData save with %d.",lookupData.getLookupId()));
    }


    /**
     * Method use to update new filed into db & cache
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse updateLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request updateLookupData :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getLookupId())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupId missing.");
        } else if (isNull(lookupDataRequest.getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupValue missing.");
        } else if (isNull(lookupDataRequest.getLookupType())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupType missing.");
        } else if (isNull(lookupDataRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        Optional<LookupData> lookupData = this.lookupDataRepository.findById(lookupDataRequest.getLookupId());
        if (lookupData.isPresent()) {
            lookupData.get().setLookupValue(lookupDataRequest.getLookupValue());
            lookupData.get().setLookupType(lookupDataRequest.getLookupType());
            if (!isNull(lookupDataRequest.getDescription())) {
                lookupData.get().setDescription(lookupDataRequest.getDescription());
            }
            this.lookupDataRepository.save(lookupData.get());
            this.initialize(); // its update and add new also
            return new AppResponse(ProcessUtil.SUCCESS, String.format(
                "LookupData update with %d.", lookupDataRequest.getLookupId()));
        }
        return new AppResponse(ProcessUtil.ERROR, String.format(
            "LookupData not found with %d.", lookupDataRequest.getLookupId()));
    }

    /**
     * Method use to fetch sub lookup by ParentId
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSubLookupByParentId(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request fetchSubLookupByParentId :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getParentLookupId())) {
            return new AppResponse(ProcessUtil.ERROR, "ParentLookupId missing.");
        }
        if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        if (!this.appUserRepository.findByUsernameAndStatus(lookupDataRequest.getAccessUserDetail().getUsername(),
                Status.ACTIVE.getLookupValue()).isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataResponse> lookupDataResponses = new ArrayList<>();
        Optional<LookupData> parentLookup = this.lookupDataRepository.findByParentLookupAndAppUserUsername(
            lookupDataRequest.getParentLookupId(), lookupDataRequest.getAccessUserDetail().getUsername());
        if (parentLookup.isPresent()) {
            LookupDataResponse parentLookupDataResponse = new LookupDataResponse();
            this.fillLookupDateDto(parentLookup.get(), parentLookupDataResponse);
            appSettingDetail.put(PARENT_LOOKUP_DATA, parentLookupDataResponse);
            if (!isNull(parentLookup.get().getLookupChildren())) {
                for (LookupData lookup: parentLookup.get().getLookupChildren()) {
                    LookupDataResponse childLookupDataResponse = new LookupDataResponse();
                    this.fillLookupDateDto(lookup, childLookupDataResponse);
                    lookupDataResponses.add(childLookupDataResponse);
                }
            }
            appSettingDetail.put(SUB_LOOKUP_DATA, lookupDataResponses);
            return new AppResponse(ProcessUtil.SUCCESS,"Data fetch successfully.", appSettingDetail);
        }
        return new AppResponse(ProcessUtil.ERROR, String.format("LookupData not found with %s.", lookupDataRequest));
    }

    /**
     * Method use to fetch lookup by lookup type
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchLookupByLookupType(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request fetchLookupByLookupType :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getLookupType())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupType missing.");
        }
        if (lookupDataRequest.isValidate()) {
            if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
                return new AppResponse(ProcessUtil.ERROR, "Username missing.");
            }
            if (!this.appUserRepository.findByUsernameAndStatus(lookupDataRequest.getAccessUserDetail().getUsername(),
                Status.ACTIVE.getLookupValue()).isPresent()) {
                return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
            }
        }
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataResponse> lookupDataResponses = new ArrayList<>();
        Optional<LookupData> parentLookup = this.lookupDataRepository.findByLookupType(lookupDataRequest.getLookupType());
        if (parentLookup.isPresent()) {
            LookupDataResponse parentLookupDataResponse = new LookupDataResponse();
            this.fillLookupDateDto(parentLookup.get(), parentLookupDataResponse);
            appSettingDetail.put(PARENT_LOOKUP_DATA, parentLookupDataResponse);
            if (!isNull(parentLookup.get().getLookupChildren())) {
                for (LookupData lookup: parentLookup.get().getLookupChildren()) {
                    LookupDataResponse childLookupDataResponse = new LookupDataResponse();
                    this.fillLookupDateDto(lookup, childLookupDataResponse);
                    lookupDataResponses.add(childLookupDataResponse);
                }
            }
            appSettingDetail.put(SUB_LOOKUP_DATA, lookupDataResponses);
            return new AppResponse(ProcessUtil.SUCCESS,"Data fetch successfully.", appSettingDetail);
        }
        return new AppResponse(ProcessUtil.ERROR, String.format("LookupData not found with %s.", lookupDataRequest));
    }

    /**
     * Method use to fetch all lookup by user id
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchAllLookup(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request fetchAllLookup :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        List<LookupData> lookupDataList = this.lookupDataRepository.fetchAllLookup(
            lookupDataRequest.getAccessUserDetail().getUsername());
        List<LookupDataResponse> lookupDataResponses = new ArrayList<>();
        if (!lookupDataList.isEmpty()) {
            for (LookupData lookup: lookupDataList) {
                LookupDataResponse lookupDataResponse = new LookupDataResponse();
                this.fillLookupDateDto(lookup, lookupDataResponse);
                lookupDataResponses.add(lookupDataResponse);
            }
        }
        return new AppResponse(ProcessUtil.SUCCESS,"Data fetch successfully.", lookupDataResponses);
    }

    /**
     * Method use to delete the lookUp by lookup id and username
     * @param lookupDataRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request deleteLookupData :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getLookupId())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData id missing.");
        }
        if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        this.lookupDataRepository.deleteById(lookupDataRequest.getLookupId());
        this.initialize(); // its update and add new also
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "LookupData delete with %d.", lookupDataRequest.getLookupId()));
    }

    /**
     * Method use to download lookup template
     * @return ByteArrayOutputStream
     * */
    @Override
    public ByteArrayOutputStream downloadLookupTemplateFile() throws Exception {
        String basePath = this.tempStoreDirectory + File.separator;
        ClassLoader cl = this.getClass().getClassLoader();
        InputStream inputStream = cl.getResourceAsStream(this.bulkExcel.LOOKUP_TEMPLATE);
        String fileUploadPath = basePath + System.currentTimeMillis() + this.bulkExcel.XLSX_EXTENSION;
        FileOutputStream fileOut = new FileOutputStream(fileUploadPath);
        IOUtils.copy(inputStream, fileOut);
        // after copy the stream into file close
        if (inputStream != null) {
            inputStream.close();
        }
        // 2nd insert data to newly copied file. So that template couldn't be changed.
        XSSFWorkbook workbook = new XSSFWorkbook(new File(fileUploadPath));
        this.bulkExcel.setWb(workbook);
        XSSFSheet sheet = workbook.getSheet(this.bulkExcel.LOOKUP);
        this.bulkExcel.setSheet(sheet);
        this.bulkExcel.fillBulkHeader(0, this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE);
        // Priority
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();
        // read the file
        File file = new File(fileUploadPath);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(FileUtils.readFileToByteArray(file));
        file.delete();
        return byteArrayOutputStream;
    }

    /**
     * Method use to download lookup file with content
     * @param lookupDataRequest
     * @return ByteArrayOutputStream
     * */
    @Override
    public ByteArrayOutputStream downloadLookup(LookupDataRequest lookupDataRequest) throws Exception {
        logger.info("Request deleteLookupData :- " + lookupDataRequest);
        if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            throw new Exception("AppUser username missing");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            throw new Exception("AppUser not found");
        }
        List<LookupData> lookupDataList;
        if (ProcessUtil.isNull(lookupDataRequest.getParentLookupId())) {
            lookupDataList = this.lookupDataRepository.fetchAllLookup(
                lookupDataRequest.getAccessUserDetail().getUsername());
        } else {
            Optional<LookupData> parentLookup = this.lookupDataRepository.findByParentLookupAndAppUserUsername(
                lookupDataRequest.getParentLookupId(), lookupDataRequest.getAccessUserDetail().getUsername());
            if (!parentLookup.isPresent()) {
                throw new Exception("ParentLookup not found");
            }
            lookupDataList = parentLookup.get().getLookupChildren()
                .stream().collect(Collectors.toList());
        }
        XSSFWorkbook workbook = new XSSFWorkbook();
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(this.bulkExcel.LOOKUP);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE);
        lookupDataList.forEach(sourceJob -> {
            rowCount.getAndIncrement();
            List<String> dataCellValue = new ArrayList<>();
            dataCellValue.add(sourceJob.getLookupType());
            dataCellValue.add(sourceJob.getLookupValue());
            dataCellValue.add(sourceJob.getDescription());
            this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    /**
     * Method use to upload lookup file with content
     * @param fileObject
     * @return ByteArrayOutputStream
     * */
    @Override
    public AppResponse uploadLookup(FileUploadRequest fileObject) throws Exception {
        logger.info("Request for bulk uploading file!");
        Gson gson = new Gson();
        LookupDataRequest lookupDataRequest = gson.fromJson((String) fileObject.getData(), LookupDataRequest.class);
        if (isNull(lookupDataRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        } else if (!fileObject.getFile().getContentType().equalsIgnoreCase(this.bulkExcel.SHEET_NAME)) {
            logger.info("File Type " + fileObject.getFile().getContentType());
            return new AppResponse(ProcessUtil.ERROR, "You can upload only .xlsx extension file.");
        }
        // fill the stream with file into work-book
        Optional<LookupData> lookupFileLimit = this.lookupDataRepository.findByLookupType(LookupDetailUtil.LOOKUP_UPLOAD_LIMIT);
        XSSFWorkbook workbook = new XSSFWorkbook(fileObject.getFile().getInputStream());
        if (isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new AppResponse(ProcessUtil.ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(this.bulkExcel.LOOKUP);
        if(isNull(sheet)) {
            return new AppResponse(ProcessUtil.ERROR, "Sheet not found with (Job-Add)");
        } else if (sheet.getLastRowNum() < 1) {
            return new AppResponse(ProcessUtil.ERROR,  "You can't upload empty file.");
        } else if(sheet.getLastRowNum() > Long.valueOf(lookupFileLimit.get().getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, String.format("File support %s rows at a time.",
                lookupFileLimit.get().getLookupValue()));
        }
        List<LookupValidation> lookupValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                LookupValidation lookupValidation = new LookupValidation();
                lookupValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.LOOKUP_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (i==0) {
                        lookupValidation.setLookupType(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==1) {
                        lookupValidation.setLookupValue(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==2) {
                        lookupValidation.setDescription(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                lookupValidation.isValidLookup();
                Optional<LookupData> isAlreadyExistLookup = this.lookupDataRepository.findByLookupType(
                     lookupValidation.getLookupType());
                if (isAlreadyExistLookup.isPresent()) {
                    lookupValidation.setErrorMsg(String.format("LookupType %s already in use at row %s.<br>",
                        lookupValidation.getLookupType(), lookupValidation.getRowCounter()));
                }
                if (!isNull(lookupValidation.getErrorMsg())) {
                    errors.add(lookupValidation.getErrorMsg());
                    continue;
                }
                lookupValidations.add(lookupValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR, String.format("Total %d source jobs invalid.",
                errors.size()), errors);
        }
        lookupValidations.forEach(lookupValidation -> {
            // save the job and scheduler
            LookupData lookupData = new LookupData();
            lookupData.setLookupValue(lookupValidation.getLookupValue());
            lookupData.setLookupType(lookupValidation.getLookupType());
            if (!isNull(lookupValidation.getDescription())) {
                lookupData.setDescription(lookupValidation.getDescription());
            }
            if (!isNull(lookupDataRequest.getParentLookupId())) {
                Optional<LookupData> parentLookupData = this.lookupDataRepository
                        .findById(lookupDataRequest.getParentLookupId());
                if (parentLookupData.isPresent()) {
                    lookupData.setParentLookup(parentLookupData.get());
                }
            }
            lookupData.setAppUser(appUser.get());
            this.addNewLookupData(this.lookupDataRepository.save(lookupData));
        });
        return new AppResponse(ProcessUtil.SUCCESS,"Data save successfully.");
    }

    private void fillLookupDateDto(LookupData lookupData, LookupDataResponse lookupDataResponse) {
        lookupDataResponse.setLookupId(lookupData.getLookupId());
        lookupDataResponse.setLookupValue(lookupData.getLookupValue());
        lookupDataResponse.setLookupType(lookupData.getLookupType());
        lookupDataResponse.setDescription(lookupData.getDescription());
        lookupDataResponse.setDateCreated(lookupData.getDateCreated());
    }

    private LookupDataResponse getLookupDataDetail(LookupData lookupData) {
        LookupDataResponse parentLookupData = new LookupDataResponse();
        parentLookupData.setLookupId(lookupData.getLookupId());
        parentLookupData.setLookupType(lookupData.getLookupType());
        parentLookupData.setLookupValue(lookupData.getLookupValue());
        parentLookupData.setDescription(lookupData.getDescription());
        parentLookupData.setDateCreated(lookupData.getDateCreated());
        if (!ProcessUtil.isNull(lookupData.getLookupChildren()) && lookupData.getLookupChildren().size() > 0) {
            parentLookupData.setLookupChildren(lookupData.getLookupChildren()
                .stream().map(childLookup -> {
                    LookupDataResponse childLookupData =new LookupDataResponse();
                    childLookupData.setLookupId(childLookup.getLookupId());
                    childLookupData.setLookupType(childLookup.getLookupType());
                    childLookupData.setLookupValue(childLookup.getLookupValue());
                    childLookupData.setDescription(childLookup.getDescription());
                    childLookupData.setDateCreated(childLookup.getDateCreated());
                    return childLookupData;
                }).collect(Collectors.toSet()));
        }
        return parentLookupData;
    }

    public LookupDataResponse getParentLookupById(String lookupType) {
        return this.lookupCacheMap.get(lookupType);
    }

    public LookupDataResponse getChildLookupById(String parentLookupType, String childLookupType) {
        return this.getParentLookupById(parentLookupType).getLookupChildren().stream()
            .filter(childLookup -> childLookupType.equals(childLookup.getLookupType()))
            .findAny().orElse(null);
    }

    public Map<String, LookupDataResponse> getLookupCacheMap() {
        return lookupCacheMap;
    }

    public void setLookupCacheMap(Map<String, LookupDataResponse> lookupCacheMap) {
        this.lookupCacheMap = lookupCacheMap;
    }

}
