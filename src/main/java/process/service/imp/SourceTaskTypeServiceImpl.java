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
    private STTFLinkSTTRepository sttfLinkSTTRepository;
    @Autowired
    private STTSLinkSTTFRepository sttsLinkSTTFRepository;
    @Autowired
    private STTCLinkSTTSRepository sttcLinkSTTSRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private CredentialRepository credentialRepository;

    /**
     * Method use to add STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTT(STTRequest sttRequest) throws Exception {
        logger.info("Request addSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
        } else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if ((sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with kafka type missing.");
        }
        STT stt = new STT();
        stt.setServiceName(sttRequest.getServiceName());
        stt.setDescription(sttRequest.getDescription());
        stt.setTaskType(sttRequest.getTaskType());
        stt.setDefault(sttRequest.isDefaultStt());
        stt.setHomePage(sttRequest.getHomePage());
        stt.setServiceId(sttRequest.getServiceId());
        if (!ProcessUtil.isNull(sttRequest.getCredentialId())) {
            Optional<Credential> credential = this.credentialRepository.findByCredentialIdAndUsernameAndStatus(
                sttRequest.getCredentialId(), sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
            if (credential.isPresent()) {
                stt.setCredential(credential.get());
            }
        }
        stt.setStatus(Status.ACTIVE.getLookupValue());
        stt.setAppUser(adminUser.get());
        stt = this.sttRepository.save(stt);
        if (sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(this.lookupDataCacheService
                .getParentLookupById(LookupDetailUtil.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url invalid.");
            }
            ApiTaskType apiTaskType = new ApiTaskType();
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            apiTaskType.setStatus(Status.ACTIVE.getLookupValue());
            apiTaskType.setStt(stt);
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = sttRequest.getKafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Description missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "TopicName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
            }
            KafkaTaskType kafkaTaskType = new KafkaTaskType();
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            kafkaTaskType.setStatus(Status.ACTIVE.getLookupValue());
            kafkaTaskType.setStt(stt);
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        // link app user stt giving service status
        AppUserSTT appUserSTT = new AppUserSTT();
        appUserSTT.setStt(stt);
        appUserSTT.setStatus(stt.getStatus());
        appUserSTT.setAppUser(adminUser.get());
        appUserSTT.setCreatedBy(adminUser.get());
        this.appUserSTTRepository.save(appUserSTT);
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STT save with %d.", stt.getSttId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
        }else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if ((sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with kafka type missing.");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            sttRequest.getSttId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        } else if (!sttOptional.get().getTaskType().equals(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType cannot change to different taskType.");
        }
        sttOptional.get().setServiceName(sttRequest.getServiceName());
        sttOptional.get().setDescription(sttRequest.getDescription());
        sttOptional.get().setTaskType(sttRequest.getTaskType());
        sttOptional.get().setDefault(sttRequest.isDefaultStt());
        sttOptional.get().setHomePage(sttRequest.getHomePage());
        sttOptional.get().setServiceId(sttRequest.getServiceId());
        if (!ProcessUtil.isNull(sttRequest.getStatus())) {
            sttOptional.get().setStatus(sttRequest.getStatus());
        }
        if (!ProcessUtil.isNull(sttRequest.getCredentialId())) {
            Optional<Credential> credential = this.credentialRepository.findByCredentialIdAndUsernameAndStatus(
                sttRequest.getCredentialId(), sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
            if (credential.isPresent()) {
                sttOptional.get().setCredential(credential.get());
            }
        }
        if (sttRequest.getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttRequest.getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(
                this.lookupDataCacheService.getParentLookupById(
                LookupDetailUtil.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url invalid.");
            }
            ApiTaskType apiTaskType = sttOptional.get().getApiTaskType().get(0);
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            // give the same status of parent type
            if (!ProcessUtil.isNull(sttRequest.getStatus())) {
                apiTaskType.setStatus(sttRequest.getStatus());
            }
            this.sttRepository.save(sttOptional.get());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = sttRequest.getKafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Description missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "TopicPattern missing.");
            }
            KafkaTaskType kafkaTaskType = sttOptional.get().getKafkaTaskType().get(0);
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            // give the same status of parent type
            if (!ProcessUtil.isNull(sttRequest.getStatus())) {
                kafkaTaskType.setStatus(sttRequest.getStatus());
            }
            sttOptional.get().getAppUserSTT()
            .stream()
            .filter(appUserSTT -> !appUserSTT.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(appUserSTT -> {
                appUserSTT.setStatus(sttRequest.getStatus());
                return appUserSTT;
            }).collect(Collectors.toList());
            this.sttRepository.save(sttOptional.get());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STT save with %d.", sttRequest.getSttId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            sttRequest.getSttId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        sttOptional.get().setStatus(Status.DELETE.getLookupValue());
        sttOptional.get().getAppUserSTT()
            .stream()
            .filter(appUserSTT -> !appUserSTT.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(appUserSTT -> {
                appUserSTT.setStatus(Status.DELETE.getLookupValue());
                return appUserSTT;
        }).collect(Collectors.toList());
        this.sttfLinkSTTRepository.deleteAllSTTFBySTTId(Status.DELETE.getLookupValue(),
            sttOptional.get().getSttId(), adminUser.get().getAppUserId());
        this.sttRepository.save(sttOptional.get());
        if (sttOptional.get().getTaskType().equals(TaskType.AWS_SQS.getLookupValue()) ||
            sttOptional.get().getTaskType().equals(TaskType.API.getLookupValue()) ||
            sttOptional.get().getTaskType().equals(TaskType.WEB_SOCKET.getLookupValue())) {
            ApiTaskType apiTaskType = sttOptional.get().getApiTaskType().get(0);
            apiTaskType.setStatus(Status.DELETE.getLookupValue());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttOptional.get().getTaskType().equals(TaskType.KAFKA.getLookupValue())) {
            KafkaTaskType kafkaTaskType = sttOptional.get().getKafkaTaskType().get(0);
            kafkaTaskType.setStatus(Status.DELETE.getLookupValue());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Stt deleted with %d.", sttRequest.getSttId()));
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
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            sttRequest.getSttId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        STTResponse sttResponse = new STTResponse();
        sttResponse.setSttId(sttOptional.get().getSttId());
        sttResponse.setServiceName(sttOptional.get().getServiceName());
        sttResponse.setDescription(sttOptional.get().getDescription());
        sttResponse.setStatus(Status.getStatusByValue(sttOptional.get().getStatus()));
        sttResponse.setTaskType(TaskType.getTaskTypeByValue(sttOptional.get().getTaskType()));
        sttResponse.setDefaultStt(IsDefault.getDefaultByValue(sttOptional.get().getDefault()));
        if (!ProcessUtil.isNull(sttOptional.get().getHomePage())) {
            sttResponse.setHomePage(this.getDBLoopUp(
                this.lookupDataRepository.findByLookupType(sttOptional.get().getHomePage())));
        }
        sttResponse.setServiceId(sttOptional.get().getServiceId());
        if (!ProcessUtil.isNull(sttOptional.get().getCredential())) {
            sttResponse.setCredentialId(sttOptional.get().getCredential().getCredentialId());
        }
        if (sttResponse.getTaskType().getLookupType().equals(TaskType.KAFKA.getLookupType())) {
            KafkaTaskType kafkaTaskType = sttOptional.get().getKafkaTaskType().get(0);
            KafkaTaskTypeResponse kafkaTaskTypeResponse = new KafkaTaskTypeResponse();
            kafkaTaskTypeResponse.setKafkaTTId(kafkaTaskType.getKafkaTaskTypeId());
            kafkaTaskTypeResponse.setTopicName(kafkaTaskType.getTopicName());
            kafkaTaskTypeResponse.setNumPartitions(kafkaTaskType.getNumPartitions());
            kafkaTaskTypeResponse.setTopicPattern(kafkaTaskType.getTopicPattern());
            sttResponse.setKafkaTaskType(kafkaTaskTypeResponse);
        } else {
            ApiTaskType apiTaskType = sttOptional.get().getApiTaskType().get(0);
            ApiTaskTypeResponse apiTaskTypeResponse = new ApiTaskTypeResponse();
            apiTaskTypeResponse.setApiTaskTypeId(apiTaskType.getApiTaskTypeId());
            apiTaskTypeResponse.setApiUrl(apiTaskType.getApiUrl());
            apiTaskTypeResponse.setHttpMethod(RequestMethod.getRequestMethodByValue(
                Long.valueOf(apiTaskType.getHttpMethod().ordinal())));
            sttResponse.setApiTaskType(apiTaskTypeResponse);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", sttRequest.getSttId()), sttResponse);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTListResponse> result = this.sttRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), Status.DELETE.getLookupValue())
        .stream().map(sttProjection -> {
            STTListResponse sttResponse = new STTListResponse();
            sttResponse.setSttId(sttProjection.getSttId());
            sttResponse.setServiceName(sttProjection.getServiceName());
            sttResponse.setDescription(sttProjection.getDescription());
            sttResponse.setStatus(Status.getStatusByValue(sttProjection.getStatus()));
            sttResponse.setTaskType(TaskType.getTaskTypeByValue(sttProjection.getTaskType()));
            sttResponse.setSttDefault(IsDefault.getDefaultByValue(sttProjection.getSttDefault()));
            sttResponse.setServiceId(sttProjection.getServiceId());
            if (!ProcessUtil.isNull(sttProjection.getHomePage())) {
                sttResponse.setHomePage(this.getDBLoopUp(
                    this.lookupDataRepository.findByLookupType(sttProjection.getHomePage())));
            }
            if (!ProcessUtil.isNull(sttProjection.getCredentialName())) {
                CredentialResponse credentialResponse = new CredentialResponse();
                credentialResponse.setCredentialName(sttProjection.getCredentialName());
                sttResponse.setCredential(credentialResponse);
            }
            sttResponse.setDateCreated(sttProjection.getDateCreated());
            sttResponse.setTotalUser(this.appUserSTTRepository.countBySttIdAndNotInStatus(
                sttProjection.getSttId(), Status.DELETE.getLookupValue()));
            sttResponse.setTotalForm(this.sttfLinkSTTRepository.countBySttIdAndNotInStatus(
                sttProjection.getSttId(), Status.DELETE.getLookupValue()));
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
        if (isNull(sttLinkUserRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttLinkUserRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(sttLinkUserRequest.getAppUserId())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttLinkUserRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByAppUserIdAndStatus(
            sttLinkUserRequest.getAppUserId(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            sttLinkUserRequest.getSttId(), sttLinkUserRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found");
        }
        if (!this.appUserSTTRepository.findBySttIdAndAppUserIdAndStatus(sttLinkUserRequest.getSttId(),
            sttLinkUserRequest.getAppUserId(), Status.ACTIVE.getLookupValue()).isPresent()) {
            AppUserSTT appUserSTT = new AppUserSTT();
            appUserSTT.setStt(sttOptional.get());
            appUserSTT.setStatus(sttOptional.get().getStatus());
            appUserSTT.setCreatedBy(adminUser.get());
            appUserSTT.setAppUser(appUser.get());
            this.appUserSTTRepository.save(appUserSTT);
            return new AppResponse(ProcessUtil.SUCCESS, "STTLinkUser successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTLinkUser already exist.");
    }

    /**
     * Method use to delete STT Link User
     * @param sttLinkUserRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception {
        logger.info("Request deleteSTTLinkUser :- " + sttLinkUserRequest);
        if (isNull(sttLinkUserRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttLinkUserRequest.getAppUserId())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserId missing.");
        } else if (isNull(sttLinkUserRequest.getAuSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttLinkUserRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<AppUserSTT> appUserSTT = this.appUserSTTRepository.findByAuSttIdAndAppUserIdAndCreatedByAndStatusNotIn(
            sttLinkUserRequest.getAuSttId(), sttLinkUserRequest.getAppUserId(), adminUser.get().getAppUserId(),
            Status.DELETE.getLookupValue());
        if (!appUserSTT.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTLinkUser not found");
        }
        appUserSTT.get().setStatus(Status.DELETE.getLookupValue());
        this.appUserSTTRepository.save(appUserSTT.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTLinkUser successfully deleted.");
    }

    /**
     * Method use to fetch all STT Link User
     * @param sttLinkUserRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception {
        logger.info("Request fetchSTTLinkUser :- " + sttLinkUserRequest);
        if (isNull(sttLinkUserRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttLinkUserRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttLinkUserRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTLinkUserListResponse> sttLinkUserResponseList = this.appUserSTTRepository
            .findBySttIdAndCreatedByAndStatusNotIn(sttLinkUserRequest.getSttId(), adminUser.get().getAppUserId(),
            Status.DELETE.getLookupValue()).stream().map(appUserSTT -> {
                STTLinkUserListResponse sttLinkUserResponse = new STTLinkUserListResponse();
                sttLinkUserResponse.setSttLinkUserId(appUserSTT.getAuSttId());
                sttLinkUserResponse.setDateCreated(appUserSTT.getDateCreated());
                sttLinkUserResponse.setStatus(Status.getStatusByValue(appUserSTT.getStatus()));
                if (!ProcessUtil.isNull(appUserSTT.getAppUser())) {
                    sttLinkUserResponse.setAppUserid(appUserSTT.getAppUser().getAppUserId());
                    sttLinkUserResponse.setUsername(appUserSTT.getAppUser().getUsername());
                    sttLinkUserResponse.setEmail(appUserSTT.getAppUser().getEmail());
                }
                return sttLinkUserResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttLinkUserResponseList);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfName missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "FormType missing.");
        }
        STTForm sttForm = new STTForm();
        sttForm.setSttfName(sttFormRequest.getSttfName());
        sttForm.setDescription(sttFormRequest.getDescription());
        sttForm.setDefault(sttFormRequest.isDefaultSttf());
        sttForm.setFormType(sttFormRequest.getFormType());
        sttForm.setStatus(Status.ACTIVE.getLookupValue());
        sttForm.setAppUser(adminUser.get());
        sttForm = this.sttfRepository.save(sttForm);
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTForm save with %d.", sttForm.getSttfId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfName missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(sttFormRequest.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "FormType missing.");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            sttFormRequest.getSttfId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        sttFormOptional.get().setSttfName(sttFormRequest.getSttfName());
        sttFormOptional.get().setDescription(sttFormRequest.getDescription());
        sttFormOptional.get().setDefault(sttFormRequest.isDefaultSttf());
        sttFormOptional.get().setFormType(sttFormRequest.getFormType());
        if (!ProcessUtil.isNull(sttFormRequest.getStatus())) {
            sttFormOptional.get().setStatus(sttFormRequest.getStatus());
            // update the status of the app user stt
            sttFormOptional.get().getSttfLink()
                .stream()
                .filter(sttfLinkSTT -> !sttfLinkSTT.getStatus()
                    .equals(Status.DELETE.getLookupValue()))
                .map(sttfLinkSTT -> {
                    sttfLinkSTT.setStatus(sttFormRequest.getStatus());
                    return sttfLinkSTT;
                }).collect(Collectors.toList());
        }
        this.sttfRepository.save(sttFormOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTForm updated with %d.", sttFormRequest.getSttfId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttFormRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            sttFormRequest.getSttfId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        sttFormOptional.get().setStatus(Status.DELETE.getLookupValue());
        sttFormOptional.get().getSttfLink()
            .stream().filter(sttfLinkSTT -> !sttfLinkSTT.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(sttfLinkSTT -> {
                sttfLinkSTT.setStatus(Status.DELETE.getLookupValue());
                return sttfLinkSTT;
            }).collect(Collectors.toList());
        this.sttsLinkSTTFRepository.deleteAllSTTSBySTTFId(Status.DELETE.getLookupValue(),
            sttFormOptional.get().getSttfId(), adminUser.get().getAppUserId());
        this.sttfRepository.save(sttFormOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTForm deleted with %d.", sttFormRequest.getSttfId()));
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
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            sttFormRequest.getSttfId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        STTFormResponse sttFormResponse = new STTFormResponse();
        sttFormResponse.setSttfId(sttFormOptional.get().getSttfId());
        sttFormResponse.setSttfName(sttFormOptional.get().getSttfName());
        sttFormResponse.setDescription(sttFormOptional.get().getDescription());
        sttFormResponse.setStatus(Status.getStatusByValue(sttFormOptional.get().getStatus()));
        sttFormResponse.setFormType(FormType.getFormTypeByValue(sttFormOptional.get().getFormType()));
        sttFormResponse.setDefaultSttf(IsDefault.getDefaultByValue(sttFormOptional.get().getDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", sttFormRequest.getSttfId()), sttFormResponse);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFormListResponse> result = this.sttfRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttfProjection -> {
                STTFormListResponse sttfResponse = new STTFormListResponse();
                sttfResponse.setSttfId(sttfProjection.getSttfId());
                sttfResponse.setSttfName(sttfProjection.getSttfName());
                sttfResponse.setDescription(sttfProjection.getDescription());
                sttfResponse.setStatus(Status.getStatusByValue(sttfProjection.getStatus()));
                sttfResponse.setFormType(FormType.getFormTypeByValue(sttfProjection.getFormType()));
                sttfResponse.setSttfDefault(IsDefault.getDefaultByValue(sttfProjection.getSttFDefault()));
                sttfResponse.setDateCreated(sttfProjection.getDateCreated());
                sttfResponse.setTotalStt(this.sttfLinkSTTRepository.countBySttfIdAndNotInStatus(
                    sttfProjection.getSttfId(), Status.DELETE.getLookupValue()));
                sttfResponse.setTotalSection(this.sttsLinkSTTFRepository.countBySttfIdAndStatusNotIn(
                    sttfProjection.getSttfId(), Status.DELETE.getLookupValue()));
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
        if (isNull(sttfLinkSTTRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttfLinkSTTRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(sttfLinkSTTRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
                sttfLinkSTTRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            sttfLinkSTTRequest.getSttfId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            sttfLinkSTTRequest.getSttId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found");
        }
        if (!this.sttfLinkSTTRepository.findBySttIdAndSttfIdAndAppUserIdAndStatus(sttfLinkSTTRequest.getSttId(),
            sttfLinkSTTRequest.getSttfId(), adminUser.get().getAppUserId(),
                Status.ACTIVE.getLookupValue()).isPresent()) {
            STTFLinkSTT sttfLinkSTT = new STTFLinkSTT();
            sttfLinkSTT.setSttf(sttFormOptional.get());
            sttfLinkSTT.setStatus(sttFormOptional.get().getStatus());
            sttfLinkSTT.setStt(sttOptional.get());
            sttfLinkSTT.setAppUser(adminUser.get());
            this.sttfLinkSTTRepository.save(sttfLinkSTT);
            return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTFLinkSTT already exist.");
    }

    /**
     * Method use to delete STTF Link STT
     * @param sttfLinkSTTRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception {
        logger.info("Request deleteSTTFLinkSTT :- " + sttfLinkSTTRequest);
        if (isNull(sttfLinkSTTRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttfLinkSTTRequest.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(sttfLinkSTTRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(sttfLinkSTTRequest.getAuSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttfLinkSTTRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTFLinkSTT> sttfLinkSTTOptional = this.sttfLinkSTTRepository.findByAuSttfIdAndSttIdAndSttfIdAndAppUserIdAndStatusNotIn(
            sttfLinkSTTRequest.getAuSttfId(), sttfLinkSTTRequest.getSttId(), sttfLinkSTTRequest.getSttfId(),
            adminUser.get().getAppUserId(), Status.DELETE.getLookupValue());
        if (!sttfLinkSTTOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTFLinkSTT not found");
        }
        sttfLinkSTTOptional.get().setStatus(Status.DELETE.getLookupValue());
        this.sttfLinkSTTRepository.save(sttfLinkSTTOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STTF Link STT
     * @param sttfLinkSTTRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception {
        logger.info("Request fetchSTTFLinkSTT :- " + sttfLinkSTTRequest);
        if (isNull(sttfLinkSTTRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttfLinkSTTRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttfLinkSTTRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFLinkSTTListResponse> sttfLinkSTTListResponseList = this.sttfLinkSTTRepository
            .findBySttfIdAndStatusNotIn(sttfLinkSTTRequest.getSttfId(), Status.DELETE.getLookupValue())
            .stream().map(appUserSTTF -> {
                STTFLinkSTTListResponse sttfLinkSTTListResponse = new STTFLinkSTTListResponse();
                sttfLinkSTTListResponse.setSttfLinkSttId(appUserSTTF.getAuSttfId());
                sttfLinkSTTListResponse.setDateCreated(appUserSTTF.getDateCreated());
                sttfLinkSTTListResponse.setStatus(Status.getStatusByValue(appUserSTTF.getStatus()));
                if (!ProcessUtil.isNull(appUserSTTF.getStt())) {
                    sttfLinkSTTListResponse.setSttId(appUserSTTF.getStt().getSttId());
                    sttfLinkSTTListResponse.setServiceName(appUserSTTF.getStt().getServiceName());
                    sttfLinkSTTListResponse.setTaskType(TaskType.getTaskTypeByValue(appUserSTTF.getStt().getTaskType()));
                    sttfLinkSTTListResponse.setSttDefault(IsDefault.getDefaultByValue(appUserSTTF.getStt().getDefault()));
                }
                return sttfLinkSTTListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttfLinkSTTListResponseList);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsOrder missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsName missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        }
        STTSection sttSection = new STTSection();
        sttSection.setSttsOrder(sttSectionRequest.getSttsOrder());
        sttSection.setSttsName(sttSectionRequest.getSttsName());
        sttSection.setDescription(sttSectionRequest.getDescription());
        sttSection.setDefault(sttSectionRequest.isDefaultStts());
        sttSection.setStatus(Status.ACTIVE.getLookupValue());
        sttSection.setAppUser(adminUser.get());
        this.sttsRepository.save(sttSection);
        sttSectionRequest.setSttsId(sttSection.getSttsId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTSection added with %d.", sttSectionRequest.getSttsId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }  else if (ProcessUtil.isNull(sttSectionRequest.getSttsOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsOrder missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsName missing.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            sttSectionRequest.getSttsId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        sttSectionOptional.get().setAppUser(adminUser.get());
        sttSectionOptional.get().setSttsName(sttSectionRequest.getSttsName());
        sttSectionOptional.get().setSttsOrder(sttSectionRequest.getSttsOrder());
        sttSectionOptional.get().setDescription(sttSectionRequest.getDescription());
        sttSectionOptional.get().setDefault(sttSectionRequest.isDefaultStts());
        if (!ProcessUtil.isNull(sttSectionRequest.getStatus())) {
            sttSectionOptional.get().setStatus(sttSectionRequest.getStatus());
            sttSectionOptional.get().getSttsLink()
                .stream()
                .filter(sttsLinkSTTF -> !sttsLinkSTTF.getStatus()
                    .equals(Status.DELETE.getLookupValue()))
                .map(sttsLinkSTTF -> {
                    sttsLinkSTTF.setStatus(sttSectionRequest.getStatus());
                    return sttsLinkSTTF;
                }).collect(Collectors.toList());
            this.sttsRepository.save(sttSectionOptional.get());
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTSection update with %d.", sttSectionRequest.getSttsId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        } else if (ProcessUtil.isNull(sttSectionRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            sttSectionRequest.getSttsId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        sttSectionOptional.get().setStatus(Status.DELETE.getLookupValue());
        sttSectionOptional.get().getSttsLink()
            .stream()
            .filter(sttsLinkSTTF -> !sttsLinkSTTF.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(sttsLinkSTTF -> {
                sttsLinkSTTF.setStatus(Status.DELETE.getLookupValue());
                return sttsLinkSTTF;
            }).collect(Collectors.toList());
        this.sttcLinkSTTSRepository.deleteAllSTTCySTTSId(Status.DELETE.getLookupValue(),
            sttSectionRequest.getSttsId(), adminUser.get().getAppUserId());
        this.sttsRepository.save(sttSectionOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTSection deleted with %d.", sttSectionRequest.getSttsId()));
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
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(sttSectionRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            sttSectionRequest.getSttsId(), sttSectionRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        STTSectionResponse sttSectionResponse = new STTSectionResponse();
        sttSectionResponse.setSttsId(sttSectionOptional.get().getSttsId());
        sttSectionResponse.setSttsName(sttSectionOptional.get().getSttsName());
        sttSectionResponse.setDescription(sttSectionOptional.get().getDescription());
        sttSectionResponse.setSttsOrder(sttSectionOptional.get().getSttsOrder());
        sttSectionResponse.setStatus(Status.getStatusByValue(sttSectionOptional.get().getStatus()));
        sttSectionResponse.setDefaultStts(IsDefault.getDefaultByValue(sttSectionOptional.get().getDefault()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSectionListResponse> result = this.sttsRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttsProjection -> {
                STTSectionListResponse sttsResponse = new STTSectionListResponse();
                sttsResponse.setSttsId(sttsProjection.getSttsId());
                sttsResponse.setSttsName(sttsProjection.getSttsName());
                sttsResponse.setDescription(sttsProjection.getDescription());
                sttsResponse.setSttsOrder(sttsProjection.getSttsOrder());
                sttsResponse.setStatus(Status.getStatusByValue(sttsProjection.getStatus()));
                sttsResponse.setSttsDefault(IsDefault.getDefaultByValue(sttsProjection.getSttsDefault()));
                sttsResponse.setDateCreated(sttsProjection.getDateCreated());
                sttsResponse.setTotalForm(this.sttsLinkSTTFRepository.countBySttsIdAndStatusNotIn(
                    sttsProjection.getSttsId(), Status.DELETE.getLookupValue()));
                sttsResponse.setTotalControl(this.sttcLinkSTTSRepository.countBySttsIdAndStatusNotIn(
                    sttsProjection.getSttsId(), Status.DELETE.getLookupValue()));
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
        if (isNull(sttsLinkSTTFRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttsLinkSTTFRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(sttsLinkSTTFRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttsLinkSTTFRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            sttsLinkSTTFRequest.getSttsId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            sttsLinkSTTFRequest.getSttfId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        if (!this.sttsLinkSTTFRepository.findBySttsIdAndSttfIdAndAppUserIdAndStatus(sttsLinkSTTFRequest.getSttsId(),
            sttsLinkSTTFRequest.getSttfId(), adminUser.get().getAppUserId(), Status.ACTIVE.getLookupValue()).isPresent()) {
            STTSLinkSTTF sttsLinkSTTF = new STTSLinkSTTF();
            sttsLinkSTTF.setStts(sttSectionOptional.get());
            sttsLinkSTTF.setStatus(sttSectionOptional.get().getStatus());
            sttsLinkSTTF.setSttf(sttFormOptional.get());
            sttsLinkSTTF.setAppUser(adminUser.get());
            this.sttsLinkSTTFRepository.save(sttsLinkSTTF);
            return new AppResponse(ProcessUtil.SUCCESS, "STTSLinkSTTF successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF already exist.");
    }

    /**
     * Method use to delete STTS Link STTF
     * @param sttsLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception {
        logger.info("Request deleteSTTSLinkSTTF :- " + sttsLinkSTTFRequest);
        if (isNull(sttsLinkSTTFRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttsLinkSTTFRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(sttsLinkSTTFRequest.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(sttsLinkSTTFRequest.getAuSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttsLinkSTTFRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSLinkSTTF> sttsLinkSTTFOptional = this.sttsLinkSTTFRepository.findByAuSttsIdAndSttsIdAndSttfIdAndAppUserIdAndStatusNotIn(
            sttsLinkSTTFRequest.getAuSttsId(), sttsLinkSTTFRequest.getSttsId(), sttsLinkSTTFRequest.getSttfId(),
            adminUser.get().getAppUserId(), Status.DELETE.getLookupValue());
        if (!sttsLinkSTTFOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF not found");
        }
        sttsLinkSTTFOptional.get().setStatus(Status.DELETE.getLookupValue());
        this.sttsLinkSTTFRepository.save(sttsLinkSTTFOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STTS Link STTF
     * @param sttsLinkSTTFRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception {
        logger.info("Request fetchSTTSLinkSTTF :- " + sttsLinkSTTFRequest);
        if (isNull(sttsLinkSTTFRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttsLinkSTTFRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttsLinkSTTFRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSLinkSTTFListResponse> sttsLinkSTTFListResponses = this.sttsLinkSTTFRepository
            .findBySttsIdAndStatusNotIn(sttsLinkSTTFRequest.getSttsId(), Status.DELETE.getLookupValue())
            .stream().map(sttsLinkSTTF -> {
                STTSLinkSTTFListResponse sttsLinkSTTFListResponse = new STTSLinkSTTFListResponse();
                sttsLinkSTTFListResponse.setSttsLinkSttfId(sttsLinkSTTF.getAuSttsId());
                sttsLinkSTTFListResponse.setDateCreated(sttsLinkSTTF.getDateCreated());
                sttsLinkSTTFListResponse.setStatus(Status.getStatusByValue(sttsLinkSTTF.getStatus()));
                if (!ProcessUtil.isNull(sttsLinkSTTF.getSttf())) {
                    sttsLinkSTTFListResponse.setFormId(sttsLinkSTTF.getSttf().getSttfId());
                    sttsLinkSTTFListResponse.setFormName(sttsLinkSTTF.getSttf().getSttfName());
                    sttsLinkSTTFListResponse.setFormType(FormType.getFormTypeByValue(sttsLinkSTTF.getSttf().getFormType()));
                    sttsLinkSTTFListResponse.setFormDefault(IsDefault.getDefaultByValue(sttsLinkSTTF.getSttf().getDefault()));
                }
                return sttsLinkSTTFListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttsLinkSTTFListResponses);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttControlRequest.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledType missing.");
        } else if (isNull(sttControlRequest.getSttcName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCName missing.");
        } else if (isNull(sttControlRequest.getSttcOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCOrder missing.");
        } else if (isNull(sttControlRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(sttControlRequest.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledName missing.");
        } else if (isNull(sttControlRequest.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledTitle missing.");
        } else if (isNull(sttControlRequest.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledWidth missing.");
        } else if (isNull(sttControlRequest.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Mandatory missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        STTControl sttControl = new STTControl();
        sttControl.setSttcOrder(sttControlRequest.getSttcOrder());
        sttControl.setSttcName(sttControlRequest.getSttcName());
        sttControl.setFiledType(sttControlRequest.getFiledType());
        sttControl.setFiledTitle(sttControlRequest.getFiledTitle());
        sttControl.setFiledName(sttControlRequest.getFiledName());
        sttControl.setDescription(sttControlRequest.getDescription());
        sttControl.setPlaceHolder(sttControlRequest.getPlaceHolder());
        sttControl.setFiledWidth(sttControlRequest.getFiledWidth());
        sttControl.setMinLength(sttControlRequest.getMinLength());
        sttControl.setMaxLength(sttControlRequest.getMaxLength());
        sttControl.setDisabled(sttControlRequest.isSttcDisabled());
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
        sttControl.setAppUser(adminUser.get());
        this.sttcRepository.save(sttControl);
        sttControlRequest.setSttcId(sttControl.getSttcId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTControl added with %d.", sttControlRequest.getSttcId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttControlRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(sttControlRequest.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledType missing.");
        } else if (isNull(sttControlRequest.getSttcName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCName missing.");
        } else if (isNull(sttControlRequest.getSttcOrder())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCOrder missing.");
        } else if (isNull(sttControlRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(sttControlRequest.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledName missing.");
        } else if (isNull(sttControlRequest.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledTitle missing.");
        } else if (isNull(sttControlRequest.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledWidth missing.");
        } else if (isNull(sttControlRequest.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Mandatory missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            sttControlRequest.getSttcId(), sttControlRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        if (FormControlType.RADIO.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.CHECKBOX.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.SELECT.getLookupValue().equals(sttControlRequest.getFiledType()) ||
            FormControlType.MULTI_SELECT.getLookupValue().equals(sttControlRequest.getFiledType())) {
            sttControlOptional.get().setFiledLkValue(sttControlRequest.getFiledLookUp());
        }
        sttControlOptional.get().setSttcOrder(sttControlRequest.getSttcOrder());
        sttControlOptional.get().setSttcName(sttControlRequest.getSttcName());
        sttControlOptional.get().setFiledType(sttControlRequest.getFiledType());
        sttControlOptional.get().setFiledTitle(sttControlRequest.getFiledTitle());
        sttControlOptional.get().setFiledName(sttControlRequest.getFiledName());
        sttControlOptional.get().setDescription(sttControlRequest.getDescription());
        sttControlOptional.get().setPlaceHolder(sttControlRequest.getPlaceHolder());
        sttControlOptional.get().setFiledWidth(sttControlRequest.getFiledWidth());
        sttControlOptional.get().setMinLength(sttControlRequest.getMinLength());
        sttControlOptional.get().setMaxLength(sttControlRequest.getMaxLength());
        sttControlOptional.get().setMandatory(sttControlRequest.isMandatory());
        sttControlOptional.get().setDefault(sttControlRequest.isSttcDefault());
        sttControlOptional.get().setDisabled(sttControlRequest.isSttcDisabled());
        sttControlOptional.get().setPattern(sttControlRequest.getPattern());
        sttControlOptional.get().setStatus(sttControlRequest.getStatus());
        sttControlOptional.get().setAppUser(adminUser.get());
        sttControlOptional.get().getSttcLink()
            .stream()
            .filter(sttcLinkSTTS -> !sttcLinkSTTS.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(sttcLinkSTTS -> {
                sttcLinkSTTS.setStatus(sttControlRequest.getStatus());
                return sttcLinkSTTS;
            }).collect(Collectors.toList());
        this.sttcRepository.save(sttControlOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTControl updated with %d.", sttControlRequest.getSttcId()));
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
                sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttControlRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            sttControlRequest.getSttcId(), sttControlRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        sttControlOptional.get().setStatus(Status.DELETE.getLookupValue());
        sttControlOptional.get().getSttcLink()
            .stream()
            .filter(sttcLinkSTTS -> !sttcLinkSTTS.getStatus()
                .equals(Status.DELETE.getLookupValue()))
            .map(sttcLinkSTTS -> {
                sttcLinkSTTS.setStatus(Status.DELETE.getLookupValue());
                return sttcLinkSTTS;
            }).collect(Collectors.toList());
        this.sttcRepository.save(sttControlOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STTControl deleted with %d.", sttControlRequest.getSttcId()));
    }

    /**
     * Method use to fetch STTC value by sttc id
     * @param sttControlRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCBySttcId(STTControlRequest sttControlRequest) throws Exception {
        logger.info("Request fetchSTTCBySttcId :- " + sttControlRequest);
        if (isNull(sttControlRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttcid missing.");
        } else if (isNull(sttControlRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttControlRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            sttControlRequest.getSttcId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        STTControlResponse sttControlResponse = new STTControlResponse();
        sttControlResponse.setSttcId(sttControlOptional.get().getSttcId());
        sttControlResponse.setSttcOrder(sttControlOptional.get().getSttcOrder());
        sttControlResponse.setSttcName(sttControlOptional.get().getSttcName());
        sttControlResponse.setDescription(sttControlOptional.get().getDescription());
        sttControlResponse.setFiledType(FormControlType.getFormControlTypeByValue(sttControlOptional.get().getFiledType()));
        sttControlResponse.setFiledTitle(sttControlOptional.get().getFiledTitle());
        sttControlResponse.setFiledName(sttControlOptional.get().getFiledName());
        sttControlResponse.setPlaceHolder(sttControlOptional.get().getPlaceHolder());
        sttControlResponse.setFiledWidth(sttControlOptional.get().getFiledWidth());
        sttControlResponse.setMinLength(sttControlOptional.get().getMinLength());
        sttControlResponse.setMaxLength(sttControlOptional.get().getMaxLength());
        sttControlResponse.setFiledLookUp(sttControlOptional.get().getFiledLkValue());
        sttControlResponse.setMandatory(IsDefault.getDefaultByValue(sttControlOptional.get().getMandatory()));
        sttControlResponse.setSttcDefault(IsDefault.getDefaultByValue(sttControlOptional.get().getDefault()));
        sttControlResponse.setSttcDisabled(IsDefault.getDefaultByValue(sttControlOptional.get().getDisabled()));
        sttControlResponse.setPattern(sttControlOptional.get().getPattern());
        sttControlResponse.setStatus(Status.getStatusByValue(sttControlOptional.get().getStatus()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.",
            sttControlRequest.getSttcId()), sttControlResponse);
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttControl.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTControlListResponse> result = this.sttcRepository.findByAppUserUsernameAndStatusNotIn(
            adminUser.get().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(sttcProjection -> {
                STTControlListResponse sttControlListResponse = new STTControlListResponse();
                sttControlListResponse.setSttcId(sttcProjection.getSttcId());
                sttControlListResponse.setSttcOrder(sttcProjection.getSttcOrder());
                sttControlListResponse.setSttcName(sttcProjection.getSttcName());
                sttControlListResponse.setFiledName(sttcProjection.getFiledName());
                sttControlListResponse.setFiledType(FormControlType.getFormControlTypeByValue(sttcProjection.getFiledType()));
                sttControlListResponse.setStatus(Status.getStatusByValue(sttcProjection.getStatus()));
                sttControlListResponse.setMandatory(IsDefault.getDefaultByValue(sttcProjection.getMandatory()));
                sttControlListResponse.setSttcDefault(IsDefault.getDefaultByValue(sttcProjection.getSttcDefault()));
                sttControlListResponse.setDateCreated(sttcProjection.getDateCreated());
                sttControlListResponse.setTotalSection(this.sttcLinkSTTSRepository.countBySttcIdAndStatusNotIn(
                    sttcProjection.getSttcId(), Status.DELETE.getLookupValue()));
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
        if (isNull(sttcLinkSTTSRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttcLinkSTTSRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(sttcLinkSTTSRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttcLinkSTTSRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            sttcLinkSTTSRequest.getSttsId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            sttcLinkSTTSRequest.getSttcId(), adminUser.get().getUsername(), Status.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found");
        }
        if (!this.sttcLinkSTTSRepository.findBySttcIdAndAppUserIdAndSttsIdAndStatus(sttcLinkSTTSRequest.getSttcId(),
            adminUser.get().getAppUserId(), sttcLinkSTTSRequest.getSttsId(), Status.ACTIVE.getLookupValue())
            .isPresent()) {
            STTCLinkSTTS sttcLinkSTTS = new STTCLinkSTTS();
            sttcLinkSTTS.setSttc(sttControlOptional.get());
            sttcLinkSTTS.setStatus(sttSectionOptional.get().getStatus());
            sttcLinkSTTS.setStts(sttSectionOptional.get());
            sttcLinkSTTS.setAppUser(adminUser.get());
            this.sttcLinkSTTSRepository.save(sttcLinkSTTS);
            return new AppResponse(ProcessUtil.SUCCESS, "STTCLinkSTTS successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTCLinkSTTS already exist.");
    }

    /**
     * Method use to delete STTC Link STTS
     * @param sttcLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception {
        logger.info("Request deleteSTTCLinkSTTS :- " + sttcLinkSTTSRequest);
        if (isNull(sttcLinkSTTSRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttcLinkSTTSRequest.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(sttcLinkSTTSRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(sttcLinkSTTSRequest.getAuSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttcLinkSTTSRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTCLinkSTTS> sttcLinkSTTSOptional = this.sttcLinkSTTSRepository.findByAuSttcIdAndSttsIdAndAppUserIdAndSttcIdAndStatusNotIn(
            sttcLinkSTTSRequest.getAuSttcId(), sttcLinkSTTSRequest.getSttsId(), adminUser.get().getAppUserId(),
            sttcLinkSTTSRequest.getSttcId(), Status.DELETE.getLookupValue());
        if (!sttcLinkSTTSOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTCLinkSTTS not found");
        }
        sttcLinkSTTSOptional.get().setStatus(Status.DELETE.getLookupValue());
        this.sttcLinkSTTSRepository.save(sttcLinkSTTSOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTCLinkSTTS successfully deleted.");
    }

    /**
     * Method use to fetch STTC Link STTS
     * @param sttcLinkSTTSRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception {
        logger.info("Request fetchSTTCLinkSTTS :- " + sttcLinkSTTSRequest);
        if (isNull(sttcLinkSTTSRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(sttcLinkSTTSRequest.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttcLinkSTTSRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTCLinkSTTSListResponse> sttcLinkSTTSListResponses = this.sttcLinkSTTSRepository
            .findBySttcIdAndStatusNotIn(sttcLinkSTTSRequest.getSttcId(), Status.DELETE.getLookupValue())
            .stream().map(sttcLinkSTTS -> {
                STTCLinkSTTSListResponse sttcLinkSTTSListResponse = new STTCLinkSTTSListResponse();
                sttcLinkSTTSListResponse.setSttcLinkSttsId(sttcLinkSTTS.getAuSttcId());
                sttcLinkSTTSListResponse.setDateCreated(sttcLinkSTTS.getDateCreated());
                sttcLinkSTTSListResponse.setStatus(Status.getStatusByValue(sttcLinkSTTS.getStatus()));
                if (!ProcessUtil.isNull(sttcLinkSTTS.getStts())) {
                    sttcLinkSTTSListResponse.setSttsId(sttcLinkSTTS.getStts().getSttsId());
                    sttcLinkSTTSListResponse.setSttsName(sttcLinkSTTS.getStts().getSttsName());
                    sttcLinkSTTSListResponse.setSttsOrder(sttcLinkSTTS.getStts().getSttsOrder());
                    sttcLinkSTTSListResponse.setSttsDefault(
                        IsDefault.getDefaultByValue(sttcLinkSTTS.getStts().getDefault()));
                }
                return sttcLinkSTTSListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS,
            "Data fetch successfully", sttcLinkSTTSListResponses);
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
            throw new Exception("Username missing.");
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
            throw new Exception("Username missing.");
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
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFileUReq.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            throw new Exception("AppUser not found");
        }
        if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT)) {
            this.sttRepository.findSttByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                Status.DELETE.getLookupValue()).forEach(stt -> {
                    if (TaskType.KAFKA.getLookupValue().equals(stt.getTaskType())) {
                        rowCount.getAndIncrement();
                        List<String> dataCellValue = new ArrayList<>();
                        dataCellValue.add(stt.getServiceName());
                        dataCellValue.add(stt.getDescription());
                        dataCellValue.add(IsDefault.getDefaultByValue(stt.getDefault()).getDescription());
                        dataCellValue.add(TaskType.getTaskTypeByValue(stt.getTaskType()).getDescription());
                        // only kafka support for this version 2.0
                        dataCellValue.add(stt.getKafkaTaskType().get(0).getTopicName());
                        dataCellValue.add(stt.getKafkaTaskType().get(0).getNumPartitions().toString());
                        this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                    }
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_FORM)) {
            this.sttfRepository.findByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                Status.DELETE.getLookupValue()).forEach(sttf -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(sttf.getSttfName());
                    dataCellValue.add(sttf.getDescription());
                    dataCellValue.add(IsDefault.getDefaultByValue(sttf.getSttFDefault()).getDescription());
                    dataCellValue.add(FormType.getFormTypeByValue(sttf.getFormType()).getDescription());
                    this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_SECTION)) {
            this.sttsRepository.findByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                Status.DELETE.getLookupValue()).forEach(stts -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(stts.getSttsOrder().toString());
                    dataCellValue.add(stts.getSttsName());
                    dataCellValue.add(stts.getDescription());
                    dataCellValue.add(IsDefault.getDefaultByValue(stts.getSttsDefault()).getDescription());
                    this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_CONTROL)) {
            this.sttcRepository.findSttControlByAppUserUsernameAndStatusNotIn(adminUser.get().getUsername(),
                Status.DELETE.getLookupValue()).forEach(sttc -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(sttc.getSttcOrder().toString());
                    dataCellValue.add(sttc.getSttcName());
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
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(sttFileUReq.getUploadType())) {
            return new AppResponse(ProcessUtil.ERROR, "Upload type missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            sttFileUReq.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
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
            return this.uploadSTT(sheet, adminUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_FORM)) {
            return this.uploadSTTForm(sheet, adminUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_SECTION)) {
            return this.uploadSTTSection(sheet, adminUser.get());
        } else if (sttFileUReq.getUploadType().equals(this.bulkExcel.STT_CONTROL)) {
            return this.uploadSTTControl(sheet, adminUser.get());
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
            return new AppResponse(ProcessUtil.ERROR, String.format(
                "Total %d STT invalid.", errors.size()), errors);
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
            appUserSTT.setCreatedBy(appUser);
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
            sttForm.setSttfName(sttfValidation.getFormName());
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
            sttSection.setSttsOrder(Long.valueOf(sttsValidation.getSectionOrder()));
            sttSection.setSttsName(sttsValidation.getSectionName());
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
            sttControl.setSttcOrder(Long.valueOf(sttcValidation.getControlOrder()));
            sttControl.setSttcName(sttcValidation.getControlName());
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
