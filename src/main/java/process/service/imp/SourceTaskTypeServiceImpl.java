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
import process.model.pojo.*;
import process.model.repository.*;
import process.payload.request.*;
import process.payload.response.*;
import process.service.LookupDataCacheService;
import process.service.SourceTaskTypeService;
import process.util.ProcessUtil;
import process.util.excel.BulkExcel;
import process.util.lookuputil.*;
import process.util.validation.*;
import javax.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class SourceTaskTypeServiceImpl implements SourceTaskTypeService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskTypeServiceImpl.class);

    @Value("${storage.efsFileDire}")
    private String tempStoreDirectory;
    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;
    @Autowired
    private ApiTaskTypeRepository apiTaskTypeRepository;
    @Autowired
    private KafkaTaskTypeRepository kafkaTaskTypeRepository;
    @Autowired
    private STTRepository sttRepository;
    @Autowired
    private STTFormRepository sttfRepository;
    @Autowired
    private STTSectionRepository sttsRepository;
    @Autowired
    private STTControlRepository sttcRepository;
    @Autowired
    private AppUserSTTRepository appUserSTTRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;

    /**
     * Method use to add STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTT(STTRequest sttRequest) throws Exception {
        logger.info("Request addSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
        } else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType missing.");
        } else if ((sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Kafka type missing.");
        }
        STT stt = new STT();
        stt.setServiceName(sttRequest.getServiceName());
        stt.setDescription(sttRequest.getDescription());
        stt.setTaskType(sttRequest.getTaskType());
        stt.setDefault(sttRequest.isDefaultStt());
        stt.setStatus(Status.ACTIVE.getLookupValue());
        if (sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(this.lookupDataCacheService
                .getParentLookupById(LookupDetailUtil.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url invalid.");
            }
            ApiTaskType apiTaskType = new ApiTaskType();
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            apiTaskType.setApiSecurityLkValue(apiTaskTypeRequest.getApiSecurityLkValue());
            apiTaskType.setStatus(Status.ACTIVE.getLookupValue());
            stt.setAppUser(appUser.get());
            stt = this.sttRepository.save(stt);
            apiTaskType.setStt(stt);
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = sttRequest.getKafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            }
            KafkaTaskType kafkaTaskType = new KafkaTaskType();
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            kafkaTaskType.setStatus(Status.ACTIVE.getLookupValue());
            stt.setAppUser(appUser.get());
            stt = this.sttRepository.save(stt);
            kafkaTaskType.setStt(stt);
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        AppUserSTT appUserSTT = new AppUserSTT();
        appUserSTT.setStt(stt);
        appUserSTT.setStatus(Status.ACTIVE.getLookupValue());
        appUserSTT.setAppUser(appUser.get());
        this.appUserSTTRepository.save(appUserSTT);
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.",
            stt.getSttId()));
    }

    /**
     * Method use to edit STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTT(STTRequest sttRequest) throws Exception {
        logger.info("Request editSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt id missing.");
        } else if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
        }else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType missing.");
        } else if ((sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Kafka type missing.");
        }
        Optional<STT> sourceTaskType = this.sttRepository.findBySttIdAndAppUserUsernameAndNotInSttStatus(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername(), Status.DELETE.getLookupValue());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        } else if (!sourceTaskType.get().getTaskType().equals(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType cannot change to different taskType.");
        }
        sourceTaskType.get().setServiceName(sttRequest.getServiceName());
        sourceTaskType.get().setDescription(sttRequest.getDescription());
        sourceTaskType.get().setTaskType(sttRequest.getTaskType());
        sourceTaskType.get().setDefault(sttRequest.isDefaultStt());
        if (!ProcessUtil.isNull(sttRequest.getStatus())) {
            sourceTaskType.get().setStatus(sttRequest.getStatus());
            // update the status of the app user stt it will update all the link user stt
            sourceTaskType.get().setAppUserSTT(sourceTaskType.get().getAppUserSTT()
                .stream().map(appUserSTT -> {
                    appUserSTT.setStatus(sttRequest.getStatus());
                    return appUserSTT;
            }).collect(Collectors.toList()));
        }
        if (sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(this.lookupDataCacheService.getParentLookupById(
                LookupDetailUtil.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url invalid.");
            }
            ApiTaskType apiTaskType = sourceTaskType.get().getApiTaskType().get(0);
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            apiTaskType.setApiSecurityLkValue(apiTaskTypeRequest.getApiSecurityLkValue());
            // give the same status of parent type
            if (!ProcessUtil.isNull(sttRequest.getStatus())) {
                apiTaskType.setStatus(sttRequest.getStatus());
            }
            this.sttRepository.save(sourceTaskType.get());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = sttRequest.getKafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            }
            KafkaTaskType kafkaTaskType = sourceTaskType.get().getKafkaTaskType().get(0);
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            // give the same status of parent type
            if (!ProcessUtil.isNull(sttRequest.getStatus())) {
                kafkaTaskType.setStatus(sttRequest.getStatus());
            }
            this.sttRepository.save(sourceTaskType.get());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.", sttRequest.getSttId()));
    }

    /**
     * Method use to delete STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTT(STTRequest sttRequest) throws Exception {
        logger.info("Request deleteSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt id missing.");
        }
        Optional<STT> sourceTaskType = this.sttRepository.findBySttIdAndAppUserUsernameAndNotInSttStatus(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername(), Status.DELETE.getLookupValue());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        sourceTaskType.get().setStatus(Status.DELETE.getLookupValue());
        sourceTaskType.get().setAppUserSTT(sourceTaskType.get().getAppUserSTT()
            .stream().map(appUserSTT -> {
                appUserSTT.setStatus(Status.DELETE.getLookupValue());
                return appUserSTT;
            }).collect(Collectors.toList()));
        if (sourceTaskType.get().getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sourceTaskType.get().getTaskType().equals(TaskType.API.getLookupValue()) ||
            sourceTaskType.get().getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskType apiTaskType = sourceTaskType.get().getApiTaskType().get(0);
            apiTaskType.setStatus(Status.DELETE.getLookupValue());
            this.sttRepository.save(sourceTaskType.get());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sourceTaskType.get().getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskType kafkaTaskType = sourceTaskType.get().getKafkaTaskType().get(0);
            kafkaTaskType.setStatus(Status.DELETE.getLookupValue());
            this.sttRepository.save(sourceTaskType.get());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT deleted with %d.", sttRequest.getSttId()));
    }

    /**
     * Method use to fetch STT value by sttId.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTBySttId(STTRequest sttRequest) throws Exception {
        logger.info("Request fetchSTTBySttId :- " + sttRequest);
        if (isNull(sttRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt sttid missing.");
        } else if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STT> sourceTaskType = this.sttRepository.findBySttIdAndAppUserUsernameAndNotInSttStatus(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername(), Status.DELETE.getLookupValue());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        STTResponse sttResponse = new STTResponse();
        sttResponse.setSttId(sourceTaskType.get().getSttId());
        sttResponse.setServiceName(sourceTaskType.get().getServiceName());
        sttResponse.setDescription(sourceTaskType.get().getDescription());
        sttResponse.setStatus(Status.getStatusByValue(sourceTaskType.get().getStatus()));
        sttResponse.setTaskType(TaskType.getTaskTypeByValue(sourceTaskType.get().getTaskType()));
        sttResponse.setDefaultStt(IsDefault.getDefaultByValue(sourceTaskType.get().getDefault()));
        if (sttResponse.getTaskType().getLookupType().equals(TaskType.KAFKA.getLookupType())) {
            KafkaTaskType kafkaTaskType = sourceTaskType.get().getKafkaTaskType().get(0);
            KafkaTaskTypeResponse kafkaTaskTypeResponse = new KafkaTaskTypeResponse();
            kafkaTaskTypeResponse.setKafkaTTId(kafkaTaskType.getKafkaTaskTypeId());
            kafkaTaskTypeResponse.setTopicName(kafkaTaskType.getTopicName());
            kafkaTaskTypeResponse.setNumPartitions(kafkaTaskType.getNumPartitions());
            kafkaTaskTypeResponse.setTopicPattern(kafkaTaskType.getTopicPattern());
            sttResponse.setKafkaTaskType(kafkaTaskTypeResponse);
        } else {
            ApiTaskType apiTaskType = sourceTaskType.get().getApiTaskType().get(0);
            ApiTaskTypeResponse apiTaskTypeResponse = new ApiTaskTypeResponse();
            apiTaskTypeResponse.setApiTaskTypeId(apiTaskType.getApiTaskTypeId());
            apiTaskTypeResponse.setApiUrl(apiTaskType.getApiUrl());
            apiTaskTypeResponse.setHttpMethod(RequestMethod.getRequestMethodByValue(
                Long.valueOf(apiTaskType.getHttpMethod().ordinal())));
            apiTaskTypeResponse.setApiSecurityLkValue(apiTaskType.getApiSecurityLkValue());
            sttResponse.setApiTaskType(apiTaskTypeResponse);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.",
            sttRequest.getSttId()), sttResponse);
    }

    /**
     * Method use to fetch STT value
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTT(STTRequest sttRequest) throws Exception {
        logger.info("Request fetchSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTListResponse> result = this.sttRepository.findByAppUserUsernameAndNotInStatus(
            appUser.get().getUsername(), Status.DELETE.getLookupValue())
        .stream().map(sttProjection -> {
            STTListResponse sttResponse = new STTListResponse();
            sttResponse.setSttId(sttProjection.getSttId());
            sttResponse.setServiceName(sttProjection.getServiceName());
            sttResponse.setDescription(sttProjection.getDescription());
            sttResponse.setStatus(Status.getStatusByValue(sttProjection.getStatus()));
            sttResponse.setTaskType(TaskType.getTaskTypeByValue(sttProjection.getTaskType()));
            sttResponse.setSttDefault(IsDefault.getDefaultByValue(sttProjection.getSttDefault()));
            sttResponse.setDateCreated(sttProjection.getDateCreated());
            sttResponse.setTotalUser(sttProjection.getTotalUser());
            sttResponse.setTotalTask(sttProjection.getTotalTask());
            sttResponse.setTotalForm(sttProjection.getTotalForm());
          return sttResponse;
        }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STT Link User
     * @param sttLinkUserRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception {
        logger.info("Request addSTTLinkUser :- " + sttLinkUserRequest);
        return null;
    }

    /**
     * Method use to delete STT Link User
     * @param sttLinkUserRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception {
        logger.info("Request deleteSTTLinkUser :- " + sttLinkUserRequest);
        return null;
    }

    /**
     * Method use to fetch all STT Link User
     * @param sttLinkUserRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception {
        logger.info("Request fetchSTTLinkUser :- " + sttLinkUserRequest);
        return null;
    }

    /**
     * Method use to STT Link STTF
     * @param sttLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTLinkSTTF(STTLinkSTTFRequest sttLinkSTTFRequest) throws Exception {
        logger.info("Request addSTTLinkSTTF :- " + sttLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to delete STT Link STTF
     * @param sttLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTLinkSTTF(STTLinkSTTFRequest sttLinkSTTFRequest) throws Exception {
        logger.info("Request deleteSTTLinkSTTF :- " + sttLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to fetch STT Link STTF
     * @param sttLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTLinkSTTF(STTLinkSTTFRequest sttLinkSTTFRequest) throws Exception {
        logger.info("Request fetchSTTLinkSTTF :- " + sttLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to add STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTF(STTFormRequest sttFormRequest) throws Exception {
        logger.info("Request addSTTF :- " + sttFormRequest);
        if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf sttfName missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf description missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf formType missing.");
        }
        STTForm sttForm = new STTForm();
        sttForm.setSttFName(sttFormRequest.getSttfName());
        sttForm.setDescription(sttFormRequest.getDescription());
        sttForm.setDefault(sttFormRequest.isDefaultSttf());
        sttForm.setFormType(sttFormRequest.getFormType());
        sttForm.setStatus(Status.ACTIVE.getLookupValue());
        sttForm.setAppUser(appUser.get());
        sttForm = this.sttfRepository.save(sttForm);
        return new AppResponse(ProcessUtil.SUCCESS,
            String.format("Sttf save with %d.", sttForm.getSttFId()));
    }

    /**
     * Method use to edit STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTF(STTFormRequest sttFormRequest) throws Exception {
        logger.info("Request editSTTF :- " + sttFormRequest);
        if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf sttfId missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf sttfName missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf description missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf formType missing.");
        }
        Optional<STTForm> sttForm = this.sttfRepository.findBySttFIdAndAppUserUsernameAndNotInStatus(
            sttFormRequest.getSttfId(), sttFormRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttForm.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf not found.");
        }
        sttForm.get().setSttFName(sttFormRequest.getSttfName());
        sttForm.get().setDescription(sttFormRequest.getDescription());
        sttForm.get().setDefault(sttFormRequest.isDefaultSttf());
        sttForm.get().setFormType(sttFormRequest.getFormType());
        if (!ProcessUtil.isNull(sttFormRequest.getStatus())) {
            sttForm.get().setStatus(sttFormRequest.getStatus());
            // update the status of the app user stt
            sttForm.get().setAppUserSTTF(sttForm.get().getAppUserSTTF()
                .stream().map(appUserSTTF -> {
                    appUserSTTF.setStatus(sttFormRequest.getStatus());
                    return appUserSTTF;
            }).collect(Collectors.toList()));
        }
        this.sttfRepository.save(sttForm.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
             "Sttf updated with %d.", sttFormRequest.getSttfId()));
    }

    /**
     * Method use to delete STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTF(STTFormRequest sttFormRequest) throws Exception {
        logger.info("Request deleteSTTF :- " + sttFormRequest);
        if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf id missing.");
        }
        Optional<STTForm> sttForm = this.sttfRepository.findBySttFIdAndAppUserUsernameAndNotInStatus(
            sttFormRequest.getSttfId(), sttFormRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttForm.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf not found.");
        }
        sttForm.get().setStatus(Status.DELETE.getLookupValue());
        sttForm.get().setAppUserSTTF(sttForm.get().getAppUserSTTF()
            .stream().map(appUserSTTF -> {
                appUserSTTF.setStatus(Status.DELETE.getLookupValue());
                return appUserSTTF;
            }).collect(Collectors.toList()));
        this.sttfRepository.save(sttForm.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTF deleted with %d.", sttFormRequest.getSttfId()));
    }

    /**
     * Method use to fetch STTF value by sttfId.
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFBySttfId(STTFormRequest sttFormRequest) throws Exception {
        logger.info("Request fetchSTTFBySttfId :- " + sttFormRequest);
        if (isNull(sttFormRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf sttfid missing.");
        } else if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttForm = this.sttfRepository.findBySttFIdAndAppUserUsernameAndNotInStatus(
            sttFormRequest.getSttfId(), sttFormRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttForm.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf not found.");
        }
        STTFormResponse sttFormResponse = new STTFormResponse();
        sttFormResponse.setSttFId(sttForm.get().getSttFId());
        sttFormResponse.setSttFName(sttForm.get().getSttFName());
        sttFormResponse.setDescription(sttForm.get().getDescription());
        sttFormResponse.setStatus(Status.getStatusByValue(sttForm.get().getStatus()));
        sttFormResponse.setFormType(FormType.getFormTypeByValue(sttForm.get().getFormType()));
        sttFormResponse.setDefaultSttf(IsDefault.getDefaultByValue(sttForm.get().getDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.",
            sttFormRequest.getSttfId()), sttFormResponse);
    }

    /**
     * Method use to fetch STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTF(STTFormRequest sttFormRequest) throws Exception {
        logger.info("Request fetchSTTF :- " + sttFormRequest);
        if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFormListResponse> result = this.sttfRepository.findByAppUserUsernameAndNotInStatus(
            appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttfProjection -> {
                STTFormListResponse sttfResponse = new STTFormListResponse();
                sttfResponse.setSttFId(sttfProjection.getSttFId());
                sttfResponse.setSttFName(sttfProjection.getSttFName());
                sttfResponse.setDescription(sttfProjection.getDescription());
                sttfResponse.setStatus(Status.getStatusByValue(sttfProjection.getStatus()));
                sttfResponse.setFormType(FormType.getFormTypeByValue(sttfProjection.getFormType()));
                sttfResponse.setSttfDefault(IsDefault.getDefaultByValue(sttfProjection.getSttFDefault()));
                sttfResponse.setDateCreated(sttfProjection.getDateCreated());
                sttfResponse.setTotalStt(sttfResponse.getTotalStt());
                sttfResponse.setTotalSection(sttfResponse.getTotalSection());
                sttfResponse.setTotalControl(sttfResponse.getTotalControl());
                return sttfResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTF Link STT
     * @param sttfLinkSTTRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception {
        logger.info("Request addSTTFLinkSTT :- " + sttfLinkSTTRequest);
        return null;
    }

    /**
     * Method use to delete STTF Link STT
     * @param sttfLinkSTTRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception {
        logger.info("Request deleteSTTFLinkSTT :- " + sttfLinkSTTRequest);
        return null;
    }

    /**
     * Method use to fetch STTF Link STT
     * @param sttfLinkSTTRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception {
        logger.info("Request fetchSTTFLinkSTT :- " + sttfLinkSTTRequest);
        return null;
    }

    /**
     * Method use to STTF Link STTS
     * @param sttfLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTFLinkSTTS(STTFLinkSTTSRequest sttfLinkSTTSRequest) throws Exception {
        logger.info("Request addSTTFLinkSTTS :- " + sttfLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to delete STTF Link STTS
     * @param sttfLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTFLinkSTTS(STTFLinkSTTSRequest sttfLinkSTTSRequest) throws Exception {
        logger.info("Request deleteSTTFLinkSTTS :- " + sttfLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to fetch STTF Link STTS
     * @param sttfLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFLinkSTTS(STTFLinkSTTSRequest sttfLinkSTTSRequest) throws Exception {
        logger.info("Request fetchSTTFLinkSTTS :- " + sttfLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to add STTS value
     * @param sttSectionRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTS(STTSectionRequest sttSectionRequest) throws Exception {
        logger.info("Request addSTTS :- " + sttSectionRequest);
        if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts order missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts name missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts description missing.");
        }
        STTSection sttSection = new STTSection();
        sttSection.setSttSOrder(sttSectionRequest.getSttsOrder());
        sttSection.setSttSName(sttSectionRequest.getSttsName());
        sttSection.setDescription(sttSectionRequest.getDescription());
        sttSection.setDefault(sttSectionRequest.isDefaultStts());
        sttSection.setStatus(Status.ACTIVE.getLookupValue());
        sttSection.setAppUser(appUser.get());
        this.sttsRepository.save(sttSection);
        sttSectionRequest.setSttsId(sttSection.getSttSId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Stts added with %d.", sttSectionRequest.getSttsId()));
    }

    /**
     * Method use to edit STTS value
     * @param sttSectionRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) throws Exception {
        logger.info("Request editSTTS :- " + sttSectionRequest);
        if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts sttsId missing.");
        }  else if (ProcessUtil.isNull(sttSectionRequest.getSttsOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts order missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts name missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts description missing.");
        }
        Optional<STTSection> sttSection = this.sttsRepository.findBySttSIdAndAppUserUsernameAndNotInStatus(
            sttSectionRequest.getSttsId(), sttSectionRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttSection.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stts not found.");
        }
        sttSection.get().setAppUser(appUser.get());
        sttSection.get().setSttSName(sttSectionRequest.getSttsName());
        sttSection.get().setSttSOrder(sttSectionRequest.getSttsOrder());
        sttSection.get().setDescription(sttSectionRequest.getDescription());
        sttSection.get().setDefault(sttSectionRequest.isDefaultStts());
        if (!ProcessUtil.isNull(sttSectionRequest.getStatus())) {
            sttSection.get().setStatus(sttSectionRequest.getStatus());
            // update the status of the app user stt
            sttSection.get().setAppUserSTTS(sttSection.get().getAppUserSTTS()
            .stream().map(appUserSTTS -> {
                appUserSTTS.setStatus(sttSectionRequest.getStatus());
                return appUserSTTS;
            }).collect(Collectors.toList()));
        }
        this.sttsRepository.save(sttSection.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
             "Stts update with %d.", sttSectionRequest.getSttsId()));
    }

    /**
     * Method use to delete STTS value
     * @param sttSectionRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest) throws Exception {
        logger.info("Request deleteSTTS :- " + sttSectionRequest);
        if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf id missing.");
        }
        Optional<STTSection> sttSection = this.sttsRepository.findBySttSIdAndAppUserUsernameAndNotInStatus(
            sttSectionRequest.getSttsId(), sttSectionRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttSection.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stts not found.");
        }
        sttSection.get().setStatus(Status.DELETE.getLookupValue());
        sttSection.get().setAppUserSTTS(sttSection.get().getAppUserSTTS()
            .stream().map(appUserSTTF -> {
                appUserSTTF.setStatus(Status.DELETE.getLookupValue());
                return appUserSTTF;
            }).collect(Collectors.toList()));
        this.sttsRepository.save(sttSection.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
             "STTS deleted with %d.", sttSectionRequest.getSttsId()));
    }

    /**
     * Method use to fetch STTS value by stts id
     * @param sttSectionRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSBySttsId(STTSectionRequest sttSectionRequest) throws Exception {
        logger.info("Request fetchSTTSBySttsId :- " + sttSectionRequest);
        if (isNull(sttSectionRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "Stts sttsid missing.");
        } else if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSection = this.sttsRepository.findBySttSIdAndAppUserUsernameAndNotInStatus(
            sttSectionRequest.getSttsId(), sttSectionRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttSection.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stts not found.");
        }
        STTSectionResponse sttSectionResponse = new STTSectionResponse();
        sttSectionResponse.setSttSId(sttSection.get().getSttSId());
        sttSectionResponse.setSttSName(sttSection.get().getSttSName());
        sttSectionResponse.setDescription(sttSection.get().getDescription());
        sttSectionResponse.setSttSOrder(sttSection.get().getSttSOrder());
        sttSectionResponse.setStatus(Status.getStatusByValue(sttSection.get().getStatus()));
        sttSectionResponse.setDefaultStts(IsDefault.getDefaultByValue(sttSection.get().getDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", sttSectionRequest.getSttsId()), sttSectionResponse);
    }

    /**
     * Method use to fetch STTS value
     * @param sttSectionRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTS(STTSectionRequest sttSectionRequest) throws Exception {
        logger.info("Request fetchSTTS :- " + sttSectionRequest);
        if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSectionListResponse> result = this.sttsRepository.findByAppUserUsernameAndNotInStatus(
            appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttsProjection -> {
                STTSectionListResponse sttsResponse = new STTSectionListResponse();
                sttsResponse.setSttSId(sttsProjection.getSttSId());
                sttsResponse.setSttSName(sttsProjection.getSttSName());
                sttsResponse.setDescription(sttsProjection.getDescription());
                sttsResponse.setSttSOrder(sttsProjection.getSttSOrder());
                sttsResponse.setStatus(Status.getStatusByValue(sttsProjection.getStatus()));
                sttsResponse.setSttsDefault(IsDefault.getDefaultByValue(sttsProjection.getSttSDefault()));
                sttsResponse.setDateCreated(sttsProjection.getDateCreated());
                sttsResponse.setTotalSTT(sttsProjection.getTotalSTT());
                sttsResponse.setTotalForm(sttsProjection.getTotalForm());
                sttsResponse.setTotalControl(sttsProjection.getTotalControl());
                return sttsResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTS Link STTF
     * @param sttsLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception {
        logger.info("Request addSTTSLinkSTTF :- " + sttsLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to delete STTS Link STTF
     * @param sttsLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception {
        logger.info("Request deleteSTTSLinkSTTF :- " + sttsLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to fetch STTS Link STTF
     * @param sttsLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception {
        logger.info("Request fetchSTTSLinkSTTF :- " + sttsLinkSTTFRequest);
        return null;
    }

    /**
     * Method use to STTS Link STTC
     * @param sttsLinkSTTCRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTSLinkSTTC(STTSLinkSTTCRequest sttsLinkSTTCRequest) throws Exception {
        logger.info("Request addSTTSLinkSTTC :- " + sttsLinkSTTCRequest);
        return null;
    }

    /**
     * Method use to delete STTS Link STTC
     * @param sttsLinkSTTCRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTSLinkSTTC(STTSLinkSTTCRequest sttsLinkSTTCRequest) throws Exception {
        logger.info("Request deleteSTTSLinkSTTC :- " + sttsLinkSTTCRequest);
        return null;
    }

    /**
     * Method use to fetch STTS Link STTC
     * @param sttsLinkSTTCRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSLinkSTTC(STTSLinkSTTCRequest sttsLinkSTTCRequest) throws Exception {
        logger.info("Request fetchSTTSLinkSTTC :- " + sttsLinkSTTCRequest);
        return null;
    }

    /**
     * Method use to add STTC value
     * @param sttControlRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTC(STTControlRequest sttControlRequest) throws Exception {
        logger.info("Request addSTTC :- " + sttControlRequest);
        if (isNull(sttControlRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        } else if (isNull(sttControlRequest.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedType missing.");
        } else if (isNull(sttControlRequest.getSttCName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc sttCName missing.");
        } else if (isNull(sttControlRequest.getSttCOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc sttCOrder missing.");
        } else if (isNull(sttControlRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc description missing.");
        } else if (isNull(sttControlRequest.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedName missing.");
        } else if (isNull(sttControlRequest.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedTitle missing.");
        } else if (isNull(sttControlRequest.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedWidth missing.");
        } else if (isNull(sttControlRequest.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc mandatory missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        STTControl sttControl = new STTControl();
        sttControl.setSttCOrder(sttControlRequest.getSttCOrder());
        sttControl.setSttCName(sttControlRequest.getSttCName());
        sttControl.setFiledType(sttControlRequest.getFiledType());
        sttControl.setFiledTitle(sttControlRequest.getFiledTitle());
        sttControl.setFiledName(sttControlRequest.getFiledName());
        sttControl.setDescription(sttControlRequest.getDescription());
        sttControl.setPlaceHolder(sttControlRequest.getPlaceHolder());
        sttControl.setFiledWidth(sttControlRequest.getFiledWidth());
        sttControl.setMinLength(sttControlRequest.getMinLength());
        sttControl.setMaxLength(sttControlRequest.getMaxLength());
        if (FormControlType.RADIO.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.CHECKBOX.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.SELECT.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.MULTI_SELECT.getLookupValue().equals(sttControlRequest.getFiledType())) {
            sttControl.setFiledLkValue(sttControlRequest.getFiledLookUp());
        }
        sttControl.setMandatory(sttControlRequest.isMandatory());
        sttControl.setDefault(IsDefault.NO_DEFAULT.getLookupValue());
        sttControl.setPattern(sttControlRequest.getPattern());
        sttControl.setStatus(Status.ACTIVE.getLookupValue());
        sttControl.setAppUser(appUser.get());
        this.sttcRepository.save(sttControl);
        sttControlRequest.setSttCId(sttControl.getSttCId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Sttc added with %d.", sttControlRequest.getSttCId()));
    }

    /**
     * Method use to edit STTC value
     * @param sttControlRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTC(STTControlRequest sttControlRequest) throws Exception {
        logger.info("Request editSTTC :- " + sttControlRequest);
        if (isNull(sttControlRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        } else if (isNull(sttControlRequest.getSttCId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc id missing.");
        } else if (isNull(sttControlRequest.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedType missing.");
        } else if (isNull(sttControlRequest.getSttCName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc sttCName missing.");
        } else if (isNull(sttControlRequest.getSttCOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc sttCOrder missing.");
        } else if (isNull(sttControlRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc description missing.");
        } else if (isNull(sttControlRequest.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedName missing.");
        } else if (isNull(sttControlRequest.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedTitle missing.");
        } else if (isNull(sttControlRequest.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc filedWidth missing.");
        } else if (isNull(sttControlRequest.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc mandatory missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
                sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControl = this.sttcRepository.findBySttCIdAndAppUserUsernameAndNotInStatus(
            sttControlRequest.getSttCId(), sttControlRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttControl.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc not found.");
        }
        if (FormControlType.RADIO.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.CHECKBOX.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.SELECT.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.MULTI_SELECT.getLookupValue().equals(sttControlRequest.getFiledType())) {
            sttControl.get().setFiledLkValue(sttControlRequest.getFiledLookUp());
        }
        sttControl.get().setSttCOrder(sttControlRequest.getSttCOrder());
        sttControl.get().setSttCName(sttControlRequest.getSttCName());
        sttControl.get().setFiledType(sttControlRequest.getFiledType());
        sttControl.get().setFiledTitle(sttControlRequest.getFiledTitle());
        sttControl.get().setFiledName(sttControlRequest.getFiledName());
        sttControl.get().setDescription(sttControlRequest.getDescription());
        sttControl.get().setPlaceHolder(sttControlRequest.getPlaceHolder());
        sttControl.get().setFiledWidth(sttControlRequest.getFiledWidth());
        sttControl.get().setMinLength(sttControlRequest.getMinLength());
        sttControl.get().setMaxLength(sttControlRequest.getMaxLength());
        sttControl.get().setMandatory(sttControlRequest.isMandatory());
        sttControl.get().setDefault(sttControlRequest.isDefaultSttC());
        sttControl.get().setPattern(sttControlRequest.getPattern());
        sttControl.get().setStatus(sttControlRequest.getStatus());
        sttControl.get().setAppUser(appUser.get());
        this.sttcRepository.save(sttControl.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Sttc updated with %d.", sttControlRequest.getSttCId()));
    }

    /**
     * Method use to delete STTC value
     * @param sttControlRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTC(STTControlRequest sttControlRequest) throws Exception {
        logger.info("Request deleteSTTC :- " + sttControlRequest);
        if (isNull(sttControlRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttControlRequest.getSttCId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc id missing.");
        }
        Optional<STTControl> sttControl = this.sttcRepository.findBySttCIdAndAppUserUsernameAndNotInStatus(
            sttControlRequest.getSttCId(), sttControlRequest.getAccessUserDetail().getUsername(),
                Status.DELETE.getLookupValue());
        if (!sttControl.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc not found.");
        }
        sttControl.get().setStatus(Status.DELETE.getLookupValue());
        sttControl.get().setAppUserSTTC(sttControl.get().getAppUserSTTC()
            .stream().map(appUserSTTC -> {
                appUserSTTC.setStatus(Status.DELETE.getLookupValue());
                return appUserSTTC;
            }).collect(Collectors.toList()));
        this.sttcRepository.save(sttControl.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTC deleted with %d.", sttControlRequest.getSttCId()));
    }

    /**
     * Method use to fetch STTC value by sttc id
     * @param sttControlRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCBySttcId(STTControlRequest sttControlRequest) throws Exception {
        logger.info("Request fetchSTTCBySttcId :- " + sttControlRequest);
        if (isNull(sttControlRequest.getSttCId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc sttcid missing.");
        } else if (isNull(sttControlRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControl = this.sttcRepository.findBySttCIdAndAppUserUsernameAndNotInStatus(
            sttControlRequest.getSttCId(), sttControlRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttControl.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttc not found.");
        }
        STTControlResponse sttControlResponse = new STTControlResponse();
        sttControlResponse.setSttCId(sttControl.get().getSttCId());
        sttControlResponse.setSttCOrder(sttControl.get().getSttCOrder());
        sttControlResponse.setSttCName(sttControl.get().getSttCName());
        sttControlResponse.setDescription(sttControl.get().getDescription());
        sttControlResponse.setFiledType(FormControlType.getFormControlTypeByValue(sttControl.get().getFiledType()));
        sttControlResponse.setFiledTitle(sttControl.get().getFiledTitle());
        sttControlResponse.setFiledName(sttControl.get().getFiledName());
        sttControlResponse.setPlaceHolder(sttControl.get().getPlaceHolder());
        sttControlResponse.setFiledWidth(sttControl.get().getFiledWidth());
        sttControlResponse.setMinLength(sttControl.get().getMinLength());
        sttControlResponse.setMaxLength(sttControl.get().getMaxLength());
        sttControlResponse.setFiledLookUp(sttControl.get().getFiledLkValue());
        sttControlResponse.setMandatory(IsDefault.getDefaultByValue(sttControl.get().getMandatory()));
        sttControlResponse.setDefaultSttc(IsDefault.getDefaultByValue(sttControl.get().getDefault()));
        sttControlResponse.setPattern(sttControl.get().getPattern());
        sttControlResponse.setStatus(Status.getStatusByValue(sttControl.get().getStatus()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.",
            sttControlRequest.getSttCId()), sttControlResponse);
    }

    /**
     * Method use to fetch STTC value
     * @param sttControl
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTC(STTControlRequest sttControl) throws Exception {
        logger.info("Request fetchSTTC :- " + sttControl);
        if (isNull(sttControl.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttControl.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTControlListResponse> result = this.sttcRepository.findByAppUserUsernameAndNotInStatus(
            appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttcProjection -> {
                STTControlListResponse sttControlListResponse = new STTControlListResponse();
                sttControlListResponse.setSttCId(sttcProjection.getSttCId());
                sttControlListResponse.setSttCOrder(sttcProjection.getSttCOrder());
                sttControlListResponse.setSttCName(sttcProjection.getSttCName());
                sttControlListResponse.setFiledName(sttcProjection.getFiledName());
                sttControlListResponse.setFiledType(FormControlType.getFormControlTypeByValue(sttcProjection.getFiledType()));
                sttControlListResponse.setStatus(Status.getStatusByValue(sttcProjection.getStatus()));
                sttControlListResponse.setMandatory(IsDefault.getDefaultByValue(sttcProjection.getMandatory()));
                sttControlListResponse.setSttcDefault(IsDefault.getDefaultByValue(sttcProjection.getSTTCDefault()));
                sttControlListResponse.setDateCreated(sttcProjection.getDateCreated());
                sttControlListResponse.setTotalStt(sttcProjection.getTotalStt());
                sttControlListResponse.setTotalForm(sttcProjection.getTotalForm());
                sttControlListResponse.setTotalSection(sttcProjection.getTotalSection());
                return sttControlListResponse;
        }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTC Link STTS
     * @param sttcLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception {
        logger.info("Request addSTTCLinkSTTS :- " + sttcLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to delete STTC Link STTS
     * @param sttcLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception {
        logger.info("Request deleteSTTCLinkSTTS :- " + sttcLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to fetch STTC Link STTS
     * @param sttcLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception {
        logger.info("Request fetchSTTCLinkSTTS :- " + sttcLinkSTTSRequest);
        return null;
    }

    /**
     * Method use to download stt* template file
     * @param sttFileUReq
     * @return AppResponse
     * */
    @Override
    public ByteArrayOutputStream downloadSTTCommonTemplateFile(STTFileUploadRequest sttFileUReq) throws Exception {
        logger.info("Request downloadSTTCommonTemplateFile :- " + sttFileUReq);
        if (isNull(sttFileUReq.getAccessUserDetail().getUsername())) {
            throw new Exception("AppUser username missing.");
        } else if (ProcessUtil.isNull(sttFileUReq.getDownloadType())) {
            throw new Exception("Download type missing.");
        }
        String basePath = this.tempStoreDirectory + File.separator;
        ClassLoader cl = this.getClass().getClassLoader();
        InputStream inputStream = cl.getResourceAsStream(this.bulkExcel.BATCH);
        String fileUploadPath = basePath + System.currentTimeMillis() + this.bulkExcel.XLSX_EXTENSION;
        FileOutputStream fileOut = new FileOutputStream(fileUploadPath);
        IOUtils.copy(inputStream, fileOut);
        // after copy the stream into file close
        if (!ProcessUtil.isNull(inputStream)) {
            inputStream.close();
        }
        // 2nd insert data to newly copied file. So that template couldn't be changed.
        XSSFWorkbook workbook = new XSSFWorkbook(new File(fileUploadPath));
        this.bulkExcel.setWb(workbook);
        String sheetName = null;
        String[] header = null;
        if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT)) {
            sheetName = this.bulkExcel.STT;
            header = this.bulkExcel.STT_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_FORM)) {
            sheetName = this.bulkExcel.STT_FORM;
            header = this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_SECTION)) {
            sheetName = this.bulkExcel.STT_SECTION;
            header = this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_CONTROL)) {
            sheetName = this.bulkExcel.STT_CONTROL;
            header = this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE;
        }
        XSSFSheet sheet = workbook.getSheet(sheetName);
        this.bulkExcel.setSheet(sheet);
        this.bulkExcel.fillBulkHeader(0, header);
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();
        File file = new File(fileUploadPath);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(FileUtils.readFileToByteArray(file));
        file.delete();
        return byteArrayOutputStream;
    }

    /**
     * Method use to download stt* file with data
     * @param sttFileUReq
     * @return AppResponse
     * */
    @Override
    public ByteArrayOutputStream downloadSTTCommon(STTFileUploadRequest sttFileUReq) throws Exception {
        logger.info("Request downloadSTTCommon :- " + sttFileUReq);
        if (isNull(sttFileUReq.getAccessUserDetail().getUsername())) {
            throw new Exception("AppUser username missing.");
        } else if (ProcessUtil.isNull(sttFileUReq.getDownloadType())) {
            throw new Exception("Download type missing.");
        }
        XSSFWorkbook workbook = new XSSFWorkbook();
        this.bulkExcel.setWb(workbook);
        String sheetName = null;
        String[] header = null;
        if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT)) {
            sheetName = this.bulkExcel.STT;
            header = this.bulkExcel.STT_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_FORM)) {
            sheetName = this.bulkExcel.STT_FORM;
            header = this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_SECTION)) {
            sheetName = this.bulkExcel.STT_SECTION;
            header = this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE;
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_CONTROL)) {
            sheetName = this.bulkExcel.STT_CONTROL;
            header = this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE;
        }
        XSSFSheet xssfSheet = workbook.createSheet(sheetName);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), header);
        // fill data
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFileUReq.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            throw new Exception("AppUser not found");
        }
        if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT)) {
            this.sttRepository.findSttByAppUserUsernameAndNotInStatus(
                appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .forEach(stt -> {
                rowCount.getAndIncrement();
                List<String> dataCellValue = new ArrayList<>();
                dataCellValue.add(stt.getServiceName());
                dataCellValue.add(stt.getDescription());
                dataCellValue.add(IsDefault.getDefaultByValue(
                    stt.getDefault()).getDescription());
                dataCellValue.add(TaskType.getTaskTypeByValue(
                    stt.getTaskType()).getDescription());
                // only kafka support for this version 2.0
                dataCellValue.add(stt.getKafkaTaskType().get(0).getTopicName());
                dataCellValue.add(stt.getKafkaTaskType().get(0).getNumPartitions().toString());
                this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
            });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_FORM)) {
            this.sttfRepository.findByAppUserUsernameAndNotInStatus(
                appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .forEach(sttf -> {
                rowCount.getAndIncrement();
                List<String> dataCellValue = new ArrayList<>();
                dataCellValue.add(sttf.getSttFName());
                dataCellValue.add(sttf.getDescription());
                dataCellValue.add(IsDefault.getDefaultByValue(
                    sttf.getSttFDefault()).getDescription());
                dataCellValue.add(FormType.getFormTypeByValue(
                    sttf.getFormType()).getDescription());
                this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
            });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_SECTION)) {
            this.sttsRepository.findByAppUserUsernameAndNotInStatus(
                appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .forEach(stts -> {
                rowCount.getAndIncrement();
                List<String> dataCellValue = new ArrayList<>();
                dataCellValue.add(stts.getSttSOrder().toString());
                dataCellValue.add(stts.getSttSName());
                dataCellValue.add(stts.getDescription());
                dataCellValue.add(IsDefault.getDefaultByValue(
                    stts.getSttSDefault()).getDescription());
                this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
            });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_CONTROL)) {
            this.sttcRepository.findSttControlByAppUserUsernameAndNotInStatus(
                appUser.get().getUsername(), Status.DELETE.getLookupValue())
            .forEach(sttc -> {
                rowCount.getAndIncrement();
                List<String> dataCellValue = new ArrayList<>();
                dataCellValue.add(sttc.getSttCOrder().toString());
                dataCellValue.add(sttc.getSttCName());
                dataCellValue.add(sttc.getDescription());
                dataCellValue.add(sttc.getFiledName());
                dataCellValue.add(sttc.getFiledTitle());
                dataCellValue.add(!ProcessUtil.isNull(sttc.getFiledWidth()) ?
                    sttc.getFiledWidth().toString(): this.bulkExcel.BLANK_VAL);
                dataCellValue.add(sttc.getPlaceHolder());
                dataCellValue.add(sttc.getPattern());
                dataCellValue.add(FormControlType.getFormControlTypeByValue(
                    sttc.getFiledType()).getDescription());
                dataCellValue.add(!ProcessUtil.isNull(sttc.getMinLength()) ?
                    sttc.getMinLength().toString(): this.bulkExcel.BLANK_VAL);
                dataCellValue.add(!ProcessUtil.isNull(sttc.getMaxLength()) ?
                    sttc.getMaxLength().toString(): this.bulkExcel.BLANK_VAL);
                dataCellValue.add(IsDefault.getDefaultByValue(
                    sttc.getMandatory()).getDescription());
                this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
            });
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    /**
     * Method use to upload stt* file with data
     * @param fileObject
     * @return AppResponse
     * */
    @Override
    public AppResponse uploadSTTCommon(FileUploadRequest fileObject) throws Exception {
        logger.info("Request uploadSTTCommon :- " + fileObject);
         if (!fileObject.getFile().getContentType().equalsIgnoreCase(this.bulkExcel.SHEET_NAME)) {
            logger.info("File Type " + fileObject.getFile().getContentType());
            return new AppResponse(ProcessUtil.ERROR, "You can upload only .xlsx extension file.");
        } else if (ProcessUtil.isNull(fileObject.getData())) {
            return new AppResponse(ProcessUtil.ERROR, "Date not found");
        }
        Gson gson = new Gson();
        STTFileUploadRequest sttFileUReq = gson.fromJson((String)
            fileObject.getData(), STTFileUploadRequest.class);
        if (isNull(sttFileUReq.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        } else if (ProcessUtil.isNull(sttFileUReq.getUploadType())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser upload type missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFileUReq.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        Optional<LookupData> uploadLimit = this.lookupDataRepository.findByLookupType(LookupDetailUtil.UPLOAD_LIMIT);
        XSSFWorkbook workbook = new XSSFWorkbook(fileObject.getFile().getInputStream());
        if (isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new AppResponse(ProcessUtil.ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = null;
        if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT) ||
            sttFileUReq.getUploadType().equals(this.bulkExcel.STT_FORM) ||
            sttFileUReq.getUploadType().equals(this.bulkExcel.STT_SECTION) ||
            sttFileUReq.getUploadType().equals(this.bulkExcel.STT_CONTROL)) {
            sheet = workbook.getSheet(sttFileUReq.getUploadType());
        }
        // target sheet upload limit validation
        if (isNull(sheet)) {
            return new AppResponse(ProcessUtil.ERROR, String.format("Sheet not found with (%s)",
                sttFileUReq.getUploadType()));
        } else if (sheet.getLastRowNum() < 1) {
            return new AppResponse(ProcessUtil.ERROR, "You can't upload empty file.");
        } else if (sheet.getLastRowNum() > Long.valueOf(uploadLimit.get().getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, String.format("File support %s rows at a time.",
                uploadLimit.get().getLookupValue()));
        }
        logger.info(String.format("Upload File Type %s", sttFileUReq.getUploadType()));
        if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT)) {
            return this.uploadSTT(sheet, appUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_FORM)) {
            return this.uploadSTTForm(sheet, appUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_SECTION)) {
            return this.uploadSTTSection(sheet, appUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_CONTROL)) {
            return this.uploadSTTControl(sheet, appUser.get());
        }
        return new AppResponse(ProcessUtil.ERROR, "Wrong upload type define.");
    }

    private AppResponse uploadSTT(XSSFSheet sheet, AppUser appUser) throws Exception {
        List<STTValidation> sttValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.STT_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.STT_HEADER_FILED_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.STT_HEADER_FILED_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                STTValidation sttValidation = new STTValidation();
                sttValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.STT_HEADER_FILED_BATCH_FILE.length; i++) {
                    int index = 0;
                    if (i == index) {
                        sttValidation.setServiceName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttValidation.setDescription(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttValidation.setDefaultSTT(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttValidation.setTaskType(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttValidation.setTopicName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttValidation.setPartitions(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                sttValidation.isValidSTT();
                if (!isNull(sttValidation.getErrorMsg())) {
                    errors.add(sttValidation.getErrorMsg());
                    continue;
                }
                sttValidations.add(sttValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR,
                String.format("Total %d STT invalid.", errors.size()), errors);
        }
        sttValidations.forEach(sttValidation -> {
            STT stt = new STT();
            stt.setServiceName(sttValidation.getServiceName());
            stt.setDescription(sttValidation.getDescription());
            stt.setTaskType(Long.valueOf(TaskType.getTaskTypeByDescription(
                sttValidation.getTaskType()).getLookupValue().toString()));
            stt.setDefault(Boolean.valueOf(IsDefault.getDefaultByDescription(
                sttValidation.getDefaultSTT()).getLookupValue().toString()));
            stt.setStatus(Status.ACTIVE.getLookupValue());
            if (stt.getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
                KafkaTaskType kafkaTaskType = new KafkaTaskType();
                kafkaTaskType.setNumPartitions(Integer.valueOf(sttValidation.getPartitions()));
                kafkaTaskType.setTopicName(sttValidation.getTopicName());
                kafkaTaskType.setTopicPattern(sttValidation.getTopicPattern());
                kafkaTaskType.setStatus(Status.ACTIVE.getLookupValue());
                stt.setAppUser(appUser);
                stt = this.sttRepository.save(stt);
                kafkaTaskType.setStt(stt);
                this.kafkaTaskTypeRepository.save(kafkaTaskType);
            }
            AppUserSTT appUserSTT = new AppUserSTT();
            appUserSTT.setStt(stt);
            appUserSTT.setStatus(Status.ACTIVE.getLookupValue());
            appUserSTT.setAppUser(appUser);
            this.appUserSTTRepository.save(appUserSTT);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

    private AppResponse uploadSTTForm(XSSFSheet sheet, AppUser appUser) throws Exception {
        List<STTFValidation> sttfValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                STTFValidation sttfValidation = new STTFValidation();
                sttfValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.STTF_HEADER_FILED_BATCH_FILE.length; i++) {
                    int index = 0;
                    if (i == index) {
                        sttfValidation.setFormName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttfValidation.setDescription(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttfValidation.setDefaultSTTF(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttfValidation.setFormType(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                sttfValidation.isValidSTTF();
                if (!isNull(sttfValidation.getErrorMsg())) {
                    errors.add(sttfValidation.getErrorMsg());
                    continue;
                }
                sttfValidations.add(sttfValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR,
                String.format("Total %d STTF invalid.", errors.size()), errors);
        }
        sttfValidations.forEach(sttfValidation -> {
            STTForm sttForm = new STTForm();
            sttForm.setSttFName(sttfValidation.getFormName());
            sttForm.setDescription(sttfValidation.getDescription());
            sttForm.setDefault(Boolean.valueOf(IsDefault.getDefaultByDescription(
                sttfValidation.getDefaultSTTF()).getLookupValue().toString()));
            sttForm.setFormType(Long.valueOf(FormType.getFormTypeByDescription(
                sttfValidation.getFormType()).getLookupValue().toString()));
            sttForm.setStatus(Status.ACTIVE.getLookupValue());
            sttForm.setAppUser(appUser);
            this.sttfRepository.save(sttForm);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

    private AppResponse uploadSTTSection(XSSFSheet sheet, AppUser appUser) throws Exception {
        List<STTSValidation> sttsValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                STTSValidation sttsValidation = new STTSValidation();
                sttsValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.STTS_HEADER_FILED_BATCH_FILE.length; i++) {
                    int index = 0;
                    if (i == index) {
                        sttsValidation.setSectionOrder(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttsValidation.setSectionName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttsValidation.setDescription(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttsValidation.setDefaultSTTS(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                sttsValidation.isValidSTTS();
                if (!isNull(sttsValidation.getErrorMsg())) {
                    errors.add(sttsValidation.getErrorMsg());
                    continue;
                }
                sttsValidations.add(sttsValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR,
                String.format("Total %d STTS invalid.", errors.size()), errors);
        }
        sttsValidations.forEach(sttsValidation -> {
            STTSection sttSection = new STTSection();
            sttSection.setSttSOrder(Long.valueOf(sttsValidation.getSectionOrder()));
            sttSection.setSttSName(sttsValidation.getSectionName());
            sttSection.setDescription(sttsValidation.getDescription());
            sttSection.setDefault(Boolean.valueOf(IsDefault.getDefaultByDescription(
                sttsValidation.getDefaultSTTS()).getLookupValue().toString()));
            sttSection.setStatus(Status.ACTIVE.getLookupValue());
            sttSection.setAppUser(appUser);
            this.sttsRepository.save(sttSection);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

    private AppResponse uploadSTTControl(XSSFSheet sheet, AppUser appUser) throws Exception {
        List<STTCValidation> sttcValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                STTCValidation sttcValidation = new STTCValidation();
                sttcValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.STTC_HEADER_FILED_BATCH_FILE.length; i++) {
                    int index = 0;
                    if (i == index) {
                        sttcValidation.setControlOrder(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setControlName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setDescription(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setFiledName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setFiledTitle(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setFiledWidth(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setPlaceHolder(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setPattern(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setFiledType(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setMinLength(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setMaxLength(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i == ++index) {
                        sttcValidation.setRequired(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                sttcValidation.isValidSTTC();
                if (!isNull(sttcValidation.getErrorMsg())) {
                    errors.add(sttcValidation.getErrorMsg());
                    continue;
                }
                sttcValidations.add(sttcValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR,
                String.format("Total %d STTC invalid.", errors.size()), errors);
        }
        sttcValidations.forEach(sttcValidation -> {
            STTControl sttControl = new STTControl();
            sttControl.setSttCOrder(Long.valueOf(sttcValidation.getControlOrder()));
            sttControl.setSttCName(sttcValidation.getControlName());
            sttControl.setDescription(sttcValidation.getDescription());
            sttControl.setFiledType(FormControlType.getFormControlTypeByDescription(
                sttcValidation.getFiledType()).getLookupValue().toString());
            sttControl.setFiledTitle(sttcValidation.getFiledTitle());
            sttControl.setFiledName(sttcValidation.getFiledName());
            sttControl.setPlaceHolder(sttcValidation.getPlaceHolder());
            if (!ProcessUtil.isNull(sttcValidation.getFiledWidth())) {
                sttControl.setFiledWidth(Long.valueOf(sttcValidation.getFiledWidth()));
            }
            if (!ProcessUtil.isNull(sttcValidation.getMinLength())) {
                sttControl.setMinLength(Long.valueOf(sttcValidation.getMinLength()));
            }
            if (!ProcessUtil.isNull(sttcValidation.getMaxLength())) {
                sttControl.setMaxLength(Long.valueOf(sttcValidation.getMaxLength()));
            }
            if (FormControlType.RADIO.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FormControlType.CHECKBOX.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FormControlType.SELECT.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FormControlType.MULTI_SELECT.getLookupValue().equals(sttcValidation.getFiledType())) {
                sttControl.setFiledLkValue(sttcValidation.getFiledLkValue());
            }
            sttControl.setMandatory(Boolean.valueOf(IsDefault.getDefaultByDescription(
                sttcValidation.getRequired()).getLookupValue().toString()));
            sttControl.setDefault(IsDefault.NO_DEFAULT.getLookupValue());
            sttControl.setPattern(sttcValidation.getPattern());
            sttControl.setStatus(Status.ACTIVE.getLookupValue());
            sttControl.setAppUser(appUser);
            this.sttcRepository.save(sttControl);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

}
