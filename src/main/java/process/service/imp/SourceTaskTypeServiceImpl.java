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
import java.util.*;
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
    private STTCInteractionsRepository sttcInteractionsRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private CredentialRepository credentialRepository;

    /**
     * Method use to add STT value in kafka|api etc.
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTT(STTRequest payload) throws Exception {
        logger.info("Request addSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
        } else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(payload.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if ((payload.getTaskType().equals(TASKTYPE_OPTION.API.getLookupValue()) ||
                payload.getTaskType().equals(TASKTYPE_OPTION.AWS_SQS.getLookupValue()) ||
                payload.getTaskType().equals(TASKTYPE_OPTION.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(payload.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with api type missing.");
        } else if (payload.getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(payload.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with kafka type missing.");
        }
        STT stt = new STT();
        stt.setServiceName(payload.getServiceName());
        stt.setDescription(payload.getDescription());
        stt.setTaskType(payload.getTaskType());
        stt.setDefault(payload.isDefaultStt());
        if (!ProcessUtil.isNull(payload.getCredentialId())) {
            Optional<Credential> credential = this.credentialRepository.findByCredentialIdAndUsernameAndStatus(
                payload.getCredentialId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            if (credential.isPresent()) {
                stt.setCredential(credential.get());
            }
        }
        stt.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        stt.setAppUser(adminUser.get());
        stt = this.sttRepository.save(stt);
        if (payload.getTaskType().equals(TASKTYPE_OPTION.AWS_SQS.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.API.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = payload.getApiTaskType();
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
            apiTaskType.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
            apiTaskType.setStt(stt);
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (payload.getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = payload.getKafkaTaskType();
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
            kafkaTaskType.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
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
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.", stt.getSttId()));
    }

    /**
     * Method use to edit STT value in kafka|api etc.
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTT(STTRequest payload) throws Exception {
        logger.info("Request editSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (ProcessUtil.isNull(payload.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "ServiceName missing.");
        }else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(payload.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if ((payload.getTaskType().equals(TASKTYPE_OPTION.API.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.AWS_SQS.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.WEB_SOCKET.getLookupValue())) &&
            ProcessUtil.isNull(payload.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with api type missing.");
        } else if (payload.getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue()) &&
            ProcessUtil.isNull(payload.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType with kafka type missing.");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        } else if (!sttOptional.get().getTaskType().equals(payload.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType cannot change to different taskType.");
        }
        sttOptional.get().setServiceName(payload.getServiceName());
        sttOptional.get().setDescription(payload.getDescription());
        sttOptional.get().setTaskType(payload.getTaskType());
        sttOptional.get().setDefault(payload.isDefaultStt());
        if (!ProcessUtil.isNull(payload.getStatus())) {
            sttOptional.get().setStatus(payload.getStatus());
        }
        if (!ProcessUtil.isNull(payload.getCredentialId())) {
            Optional<Credential> credential = this.credentialRepository.findByCredentialIdAndUsernameAndStatus(
                payload.getCredentialId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            if (credential.isPresent()) {
                sttOptional.get().setCredential(credential.get());
            }
        }
        if (payload.getTaskType().equals(TASKTYPE_OPTION.AWS_SQS.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.API.getLookupValue()) ||
            payload.getTaskType().equals(TASKTYPE_OPTION.WEB_SOCKET.getLookupValue())) {
            ApiTaskTypeRequest apiTaskTypeRequest = payload.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(
                this.lookupDataCacheService.getParentLookupById(LookupDetailUtil.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Api url invalid.");
            }
            ApiTaskType apiTaskType = sttOptional.get().getApiTaskType().get(0);
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            // give the same status of parent type
            if (!ProcessUtil.isNull(payload.getStatus())) {
                apiTaskType.setStatus(payload.getStatus());
            }
            this.sttRepository.save(sttOptional.get());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (payload.getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue())) {
            KafkaTaskTypeRequest kafkaTaskTypeRequest = payload.getKafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Partitions missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "TopicName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "TopicPattern missing.");
            }
            KafkaTaskType kafkaTaskType = sttOptional.get().getKafkaTaskType().get(0);
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            // give the same status of parent type
            if (!ProcessUtil.isNull(payload.getStatus())) {
                kafkaTaskType.setStatus(payload.getStatus());
            }
            sttOptional.get().getAppUserSTT()
            .stream()
            .filter(appUserSTT -> !appUserSTT.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(appUserSTT -> {
                appUserSTT.setStatus(payload.getStatus());
                return appUserSTT;
            }).collect(Collectors.toList());
            this.sttRepository.save(sttOptional.get());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.", payload.getSttId()));
    }

    /**
     * Method use to delete STT value in kafka|api etc.
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTT(STTRequest payload) throws Exception {
        logger.info("Request deleteSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        sttOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        sttOptional.get().getAppUserSTT().stream()
            .filter(appUserSTT -> !appUserSTT.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(appUserSTT -> {
                appUserSTT.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
                return appUserSTT;
        }).collect(Collectors.toList());
        this.sttfLinkSTTRepository.deleteAllSTTFBySTTId(APPLICATION_STATUS.DELETE.getLookupValue(),
            sttOptional.get().getSttId(), adminUser.get().getAppUserId());
        this.sttRepository.save(sttOptional.get());
        if (sttOptional.get().getTaskType().equals(TASKTYPE_OPTION.AWS_SQS.getLookupValue()) ||
            sttOptional.get().getTaskType().equals(TASKTYPE_OPTION.API.getLookupValue()) ||
            sttOptional.get().getTaskType().equals(TASKTYPE_OPTION.WEB_SOCKET.getLookupValue())) {
            ApiTaskType apiTaskType = sttOptional.get().getApiTaskType().get(0);
            apiTaskType.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttOptional.get().getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue())) {
            KafkaTaskType kafkaTaskType = sttOptional.get().getKafkaTaskType().get(0);
            kafkaTaskType.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Stt deleted with %d.", payload.getSttId()));
    }

    /**
     * Method use to fetch STT value by sttId.
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTBySttId(STTRequest payload) throws Exception {
        logger.info("Request fetchSTTBySttId :- " + payload);
        if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        STTResponse sttResponse = new STTResponse();
        sttResponse.setSttId(sttOptional.get().getSttId());
        sttResponse.setServiceName(sttOptional.get().getServiceName());
        sttResponse.setDescription(sttOptional.get().getDescription());
        sttResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttOptional.get().getStatus()));
        sttResponse.setTaskType(TASKTYPE_OPTION.getTaskTypeByValue(sttOptional.get().getTaskType()));
        sttResponse.setDefaultStt(ISDEFAULT.getDefaultByValue(sttOptional.get().getDefault()));
        if (!ProcessUtil.isNull(sttOptional.get().getCredential())) {
            sttResponse.setCredentialId(sttOptional.get().getCredential().getCredentialId());
        }
        if (sttResponse.getTaskType().getLookupType().equals(TASKTYPE_OPTION.KAFKA.getLookupType())) {
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
            apiTaskTypeResponse.setHttpMethod(REQUEST_METHOD.getRequestMethodByValue(
                Long.valueOf(apiTaskType.getHttpMethod().ordinal())));
            sttResponse.setApiTaskType(apiTaskTypeResponse);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", payload.getSttId()), sttResponse);
    }

    /**
     * Method use to fetch STT value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTT(STTRequest payload) throws Exception {
        logger.info("Request fetchSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTListResponse> result = this.sttRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue())
        .stream().map(sttProjection -> {
            STTListResponse sttResponse = new STTListResponse();
            sttResponse.setSttId(sttProjection.getSttId());
            sttResponse.setServiceName(sttProjection.getServiceName());
            sttResponse.setDescription(sttProjection.getDescription());
            sttResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttProjection.getStatus()));
            sttResponse.setTaskType(TASKTYPE_OPTION.getTaskTypeByValue(sttProjection.getTaskType()));
            sttResponse.setSttDefault(ISDEFAULT.getDefaultByValue(sttProjection.getSttDefault()));
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
                sttProjection.getSttId(), APPLICATION_STATUS.DELETE.getLookupValue()));
            sttResponse.setTotalForm(this.sttfLinkSTTRepository.countBySttIdAndNotInStatus(
                sttProjection.getSttId(), APPLICATION_STATUS.DELETE.getLookupValue()));
          return sttResponse;
        }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STT Link User
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTLinkUser(STTLinkUserRequest payload) throws Exception {
        logger.info("Request addSTTLinkUser :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getAppUserId())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByAppUserIdAndStatus(
            payload.getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found");
        }
        if (!this.appUserSTTRepository.findBySttIdAndAppUserIdAndStatus(payload.getSttId(),
            payload.getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
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
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTLinkUser(STTLinkUserRequest payload) throws Exception {
        logger.info("Request deleteSTTLinkUser :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getAppUserId())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserId missing.");
        } else if (isNull(payload.getAuSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<AppUserSTT> appUserSTT = this.appUserSTTRepository.findByAuSttIdAndAppUserIdAndCreatedByAndStatusNotIn(
            payload.getAuSttId(), payload.getAppUserId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!appUserSTT.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTLinkUser not found");
        }
        appUserSTT.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.appUserSTTRepository.save(appUserSTT.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTLinkUser successfully deleted.");
    }

    /**
     * Method use to fetch all STT Link User
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTLinkUser(STTLinkUserRequest payload) throws Exception {
        logger.info("Request fetchSTTLinkUser :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTLinkUserListResponse> sttLinkUserResponseList = this.appUserSTTRepository
            .findBySttIdAndCreatedByAndStatusNotIn(payload.getSttId(), adminUser.get().getAppUserId(),
                APPLICATION_STATUS.DELETE.getLookupValue()).stream().map(appUserSTT -> {
                STTLinkUserListResponse sttLinkUserResponse = new STTLinkUserListResponse();
                sttLinkUserResponse.setSttLinkUserId(appUserSTT.getAuSttId());
                sttLinkUserResponse.setDateCreated(appUserSTT.getDateCreated());
                sttLinkUserResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserSTT.getStatus()));
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
     * Method use to add STT Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request addSTTLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found");
        }
        if (!this.sttfLinkSTTRepository.findBySttIdAndSttfIdAndAppUserIdAndStatus(payload.getSttId(),
            payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
            STTFLinkSTT sttfLinkSTT = new STTFLinkSTT();
            sttfLinkSTT.setSttf(sttFormOptional.get());
            sttfLinkSTT.setStatus(sttFormOptional.get().getStatus());
            sttfLinkSTT.setSttfOrder(payload.getSttfOrder());
            sttfLinkSTT.setStt(sttOptional.get());
            sttfLinkSTT.setAppUser(adminUser.get());
            this.sttfLinkSTTRepository.save(sttfLinkSTT);
            return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTFLinkSTT already exist.");
    }

    /**
     * Method use to delete STT Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request deleteSTTLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(payload.getAuSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTFLinkSTT> sttfLinkSTTOptional = this.sttfLinkSTTRepository.findByAuSttfIdAndSttIdAndSttfIdAndAppUserIdAndStatusNotIn(
            payload.getAuSttfId(), payload.getSttId(), payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttfLinkSTTOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTFLinkSTT not found");
        }
        sttfLinkSTTOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttfLinkSTTRepository.save(sttfLinkSTTOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STT Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request fetchSTTLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTLinkSTTFListResponse> sttLinkSTTFListResponseList = this.sttfLinkSTTRepository
            .findBySttIdAndStatusNotIn(payload.getSttId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(appUserSTTF -> {
                STTLinkSTTFListResponse sttLinkSTTFListResponse = new STTLinkSTTFListResponse();
                sttLinkSTTFListResponse.setSttfLinkSttId(appUserSTTF.getAuSttfId());
                sttLinkSTTFListResponse.setDateCreated(appUserSTTF.getDateCreated());
                sttLinkSTTFListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserSTTF.getStatus()));
                sttLinkSTTFListResponse.setSttfOrder(appUserSTTF.getSttfOrder());
                if (!ProcessUtil.isNull(appUserSTTF.getSttf())) {
                    sttLinkSTTFListResponse.setSttfId(appUserSTTF.getSttf().getSttfId());
                    sttLinkSTTFListResponse.setSttfName(appUserSTTF.getSttf().getSttfName());
                    sttLinkSTTFListResponse.setDescription(appUserSTTF.getSttf().getDescription());
                    sttLinkSTTFListResponse.setServiceId(appUserSTTF.getSttf().getServiceId());
                    if (!ProcessUtil.isNull(appUserSTTF.getSttf().getHomePage())) {
                        sttLinkSTTFListResponse.setHomePage(this.getDBLoopUp(this.lookupDataRepository.findByLookupType(appUserSTTF.getSttf().getHomePage())));
                    }
                    sttLinkSTTFListResponse.setFormType(FORM_TYPE.getFormTypeByValue(appUserSTTF.getSttf().getFormType()));
                    sttLinkSTTFListResponse.setDefaultSttf(ISDEFAULT.getDefaultByValue(appUserSTTF.getSttf().getDefault()));
                }
                return sttLinkSTTFListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttLinkSTTFListResponseList);
    }

    /**
     * Method use to add STTF value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTF(STTFormRequest payload) throws Exception {
        logger.info("Request addSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfName missing.");
        } else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(payload.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "FormType missing.");
        }
        STTForm sttForm = new STTForm();
        sttForm.setSttfName(payload.getSttfName());
        sttForm.setDescription(payload.getDescription());
        sttForm.setDefault(payload.isDefaultSttf());
        sttForm.setFormType(payload.getFormType());
        sttForm.setHomePage(payload.getHomePage());
        sttForm.setServiceId(!ProcessUtil.isNull(payload.getServiceId()) ? payload.getServiceId() : null);
        sttForm.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        sttForm.setAppUser(adminUser.get());
        sttForm = this.sttfRepository.save(sttForm);
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTForm save with %d.", sttForm.getSttfId()));
    }

    /**
     * Method use to edit STTF value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTF(STTFormRequest payload) throws Exception {
        logger.info("Request editSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
                payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (ProcessUtil.isNull(payload.getSttfName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfName missing.");
        } else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (ProcessUtil.isNull(payload.getFormType())) {
            return new AppResponse(ProcessUtil.ERROR, "FormType missing.");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        sttFormOptional.get().setSttfName(payload.getSttfName());
        sttFormOptional.get().setDescription(payload.getDescription());
        sttFormOptional.get().setDefault(payload.isDefaultSttf());
        sttFormOptional.get().setFormType(payload.getFormType());
        sttFormOptional.get().setHomePage(payload.getHomePage());
        sttFormOptional.get().setServiceId(!ProcessUtil.isNull(payload.getServiceId()) ? payload.getServiceId() : null);
        if (!ProcessUtil.isNull(payload.getStatus())) {
            sttFormOptional.get().setStatus(payload.getStatus());
            // update the status of the app user stt
            sttFormOptional.get().getSttfLink().stream()
                .filter(sttfLinkSTT -> !sttfLinkSTT.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
                .map(sttfLinkSTT -> {
                    sttfLinkSTT.setStatus(payload.getStatus());
                    return sttfLinkSTT;
                }).collect(Collectors.toList());
        }
        this.sttfRepository.save(sttFormOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTForm updated with %d.", payload.getSttfId()));
    }

    /**
     * Method use to delete STTF value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTF(STTFormRequest payload) throws Exception {
        logger.info("Request deleteSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        sttFormOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        sttFormOptional.get().getSttfLink()
            .stream().filter(sttfLinkSTT -> !sttfLinkSTT.getStatus()
                 .equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(sttfLinkSTT -> {
                sttfLinkSTT.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
                return sttfLinkSTT;
            }).collect(Collectors.toList());
        this.sttsLinkSTTFRepository.deleteAllSTTSBySTTFId(APPLICATION_STATUS.DELETE.getLookupValue(),
            sttFormOptional.get().getSttfId(), adminUser.get().getAppUserId());
        this.sttfRepository.save(sttFormOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTForm deleted with %d.", payload.getSttfId()));
    }

    /**
     * Method use to fetch STTF value by sttfId.
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFBySttfId(STTFormRequest payload) throws Exception {
        logger.info("Request fetchSTTFBySttfId :- " + payload);
        if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found.");
        }
        STTFormResponse sttFormResponse = new STTFormResponse();
        sttFormResponse.setSttfId(sttFormOptional.get().getSttfId());
        sttFormResponse.setSttfName(sttFormOptional.get().getSttfName());
        sttFormResponse.setDescription(sttFormOptional.get().getDescription());
        sttFormResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttFormOptional.get().getStatus()));
        sttFormResponse.setFormType(FORM_TYPE.getFormTypeByValue(sttFormOptional.get().getFormType()));
        if (!ProcessUtil.isNull(sttFormOptional.get().getHomePage())) {
            sttFormResponse.setHomePage(this.getDBLoopUp(this.lookupDataRepository.findByLookupType(sttFormOptional.get().getHomePage())));
        }
        sttFormResponse.setServiceId(sttFormOptional.get().getServiceId());
        sttFormResponse.setDefaultSttf(ISDEFAULT.getDefaultByValue(sttFormOptional.get().getDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.", payload.getSttfId()), sttFormResponse);
    }

    /**
     * Method use to fetch STTF value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTF(STTFormRequest payload) throws Exception {
        logger.info("Request fetchSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFormListResponse> result = this.sttfRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttfProjection -> {
                STTFormListResponse sttfResponse = new STTFormListResponse();
                sttfResponse.setSttfId(sttfProjection.getSttfId());
                sttfResponse.setSttfName(sttfProjection.getSttfName());
                sttfResponse.setDescription(sttfProjection.getDescription());
                sttfResponse.setServiceId(sttfProjection.getServiceId());
                if (!ProcessUtil.isNull(sttfProjection.getHomePage())) {
                    sttfResponse.setHomePage(this.getDBLoopUp(this.lookupDataRepository.findByLookupType(sttfProjection.getHomePage())));
                }
                sttfResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttfProjection.getStatus()));
                sttfResponse.setFormType(FORM_TYPE.getFormTypeByValue(sttfProjection.getFormType()));
                sttfResponse.setSttfDefault(ISDEFAULT.getDefaultByValue(sttfProjection.getSttFDefault()));
                sttfResponse.setDateCreated(sttfProjection.getDateCreated());
                sttfResponse.setTotalStt(this.sttfLinkSTTRepository.countBySttfIdAndNotInStatus(
                    sttfProjection.getSttfId(), APPLICATION_STATUS.DELETE.getLookupValue()));
                sttfResponse.setTotalSection(this.sttsLinkSTTFRepository.countBySttfIdAndStatusNotIn(
                    sttfProjection.getSttfId(), APPLICATION_STATUS.DELETE.getLookupValue()));
                return sttfResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTF Link STT
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request addSTTFLinkSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        Optional<STT> sttOptional = this.sttRepository.findBySttIdAndAppUserAndSttStatusNotIn(
            payload.getSttId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found");
        }
        if (!this.sttfLinkSTTRepository.findBySttIdAndSttfIdAndAppUserIdAndStatus(payload.getSttId(),
            payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
            STTFLinkSTT sttfLinkSTT = new STTFLinkSTT();
            sttfLinkSTT.setSttf(sttFormOptional.get());
            sttfLinkSTT.setSttfOrder(payload.getSttfOrder());
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
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request deleteSTTFLinkSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(payload.getAuSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTFLinkSTT> sttfLinkSTTOptional = this.sttfLinkSTTRepository.findByAuSttfIdAndSttIdAndSttfIdAndAppUserIdAndStatusNotIn(
            payload.getAuSttfId(), payload.getSttId(), payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttfLinkSTTOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTFLinkSTT not found");
        }
        sttfLinkSTTOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttfLinkSTTRepository.save(sttfLinkSTTOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STTF Link STT
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception {
        logger.info("Request fetchSTTFLinkSTT :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFLinkSTTListResponse> sttfLinkSTTListResponseList = this.sttfLinkSTTRepository
            .findBySttfIdAndStatusNotIn(payload.getSttfId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(appUserSTTF -> {
                STTFLinkSTTListResponse sttfLinkSTTListResponse = new STTFLinkSTTListResponse();
                sttfLinkSTTListResponse.setSttfLinkSttId(appUserSTTF.getAuSttfId());
                sttfLinkSTTListResponse.setDateCreated(appUserSTTF.getDateCreated());
                sttfLinkSTTListResponse.setSttfOrder( appUserSTTF.getSttfOrder());
                sttfLinkSTTListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserSTTF.getStatus()));
                if (!ProcessUtil.isNull(appUserSTTF.getStt())) {
                    sttfLinkSTTListResponse.setSttId(appUserSTTF.getStt().getSttId());
                    sttfLinkSTTListResponse.setServiceName(appUserSTTF.getStt().getServiceName());
                    sttfLinkSTTListResponse.setTaskType(TASKTYPE_OPTION.getTaskTypeByValue(appUserSTTF.getStt().getTaskType()));
                    sttfLinkSTTListResponse.setSttDefault(ISDEFAULT.getDefaultByValue(appUserSTTF.getStt().getDefault()));
                }
                return sttfLinkSTTListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttfLinkSTTListResponseList);
    }

    /**
     * Method use to add STTF Link STTS
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request addSTTFLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        if (!this.sttsLinkSTTFRepository.findBySttsIdAndSttfIdAndAppUserIdAndStatus(payload.getSttsId(),
            payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
            STTSLinkSTTF sttsLinkSTTF = new STTSLinkSTTF();
            sttsLinkSTTF.setStts(sttSectionOptional.get());
            sttsLinkSTTF.setStatus(sttSectionOptional.get().getStatus());
            sttsLinkSTTF.setSttf(sttFormOptional.get());
            sttsLinkSTTF.setSttsOrder(payload.getSttsOrder());
            sttsLinkSTTF.setAppUser(adminUser.get());
            this.sttsLinkSTTFRepository.save(sttsLinkSTTF);
            return new AppResponse(ProcessUtil.SUCCESS, "STTSLinkSTTF successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF already exist.");
    }


    /**
     * Method use to fetch STTF Link STTS
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request deleteSTTFLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(payload.getAuSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSLinkSTTF> sttsLinkSTTFOptional = this.sttsLinkSTTFRepository.findByAuSttsIdAndSttsIdAndSttfIdAndAppUserIdAndStatusNotIn(
            payload.getAuSttsId(), payload.getSttsId(), payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttsLinkSTTFOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF not found");
        }
        sttsLinkSTTFOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttsLinkSTTFRepository.save(sttsLinkSTTFOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STTF Link STTS
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request fetchSTTFLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTFLinkSTTSListResponse> sttfLinkSTTSListResponses = this.sttsLinkSTTFRepository
            .findBySttfIdAndStatusNotIn(payload.getSttfId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttsLinkSTTF -> {
                STTFLinkSTTSListResponse sttfLinkSTTSListResponse = new STTFLinkSTTSListResponse();
                sttfLinkSTTSListResponse.setSttfLinkSttsId(sttsLinkSTTF.getAuSttsId());
                sttfLinkSTTSListResponse.setDateCreated(sttsLinkSTTF.getDateCreated());
                sttfLinkSTTSListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttsLinkSTTF.getStatus()));
                if (!ProcessUtil.isNull(sttsLinkSTTF.getStts())) {
                    sttfLinkSTTSListResponse.setSttsOrder(sttsLinkSTTF.getSttsOrder());
                    sttfLinkSTTSListResponse.setSttsId(sttsLinkSTTF.getStts().getSttsId());
                    sttfLinkSTTSListResponse.setSttsName(sttsLinkSTTF.getStts().getSttsName());
                    sttfLinkSTTSListResponse.setSttsDefault(ISDEFAULT.getDefaultByValue(sttsLinkSTTF.getStts().getDefault()));
                }
                return sttfLinkSTTSListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttfLinkSTTSListResponses);
    }

    /**
     * Method use to add STTS value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTS(STTSectionRequest payload) throws Exception {
        logger.info("Request addSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsName missing.");
        } else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        }
        STTSection sttSection = new STTSection();
        sttSection.setSttsName(payload.getSttsName());
        sttSection.setDescription(payload.getDescription());
        sttSection.setDefault(payload.isDefaultStts());
        sttSection.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        sttSection.setAppUser(adminUser.get());
        this.sttsRepository.save(sttSection);
        payload.setSttsId(sttSection.getSttsId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTSection added with %d.", payload.getSttsId()));
    }

    /**
     * Method use to edit STTS value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTS(STTSectionRequest payload) throws Exception {
        logger.info("Request editSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
             payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (ProcessUtil.isNull(payload.getSttsName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsName missing.");
        } else if (ProcessUtil.isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
             payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        sttSectionOptional.get().setAppUser(adminUser.get());
        sttSectionOptional.get().setSttsName(payload.getSttsName());
        sttSectionOptional.get().setDescription(payload.getDescription());
        sttSectionOptional.get().setDefault(payload.isDefaultStts());
        if (!ProcessUtil.isNull(payload.getStatus())) {
            sttSectionOptional.get().setStatus(payload.getStatus());
            sttSectionOptional.get().getSttsLink().stream()
                .filter(sttsLinkSTTF -> !sttsLinkSTTF.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
                .map(sttsLinkSTTF -> {
                    sttsLinkSTTF.setStatus(payload.getStatus());
                    return sttsLinkSTTF;
                }).collect(Collectors.toList());
            this.sttsRepository.save(sttSectionOptional.get());
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTSection update with %d.", payload.getSttsId()));
    }

    /**
     * Method use to delete STTS value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTS(STTSectionRequest payload) throws Exception {
        logger.info("Request deleteSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        } else if (ProcessUtil.isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        sttSectionOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        sttSectionOptional.get().getSttsLink().stream()
            .filter(sttsLinkSTTF -> !sttsLinkSTTF.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(sttsLinkSTTF -> {
                sttsLinkSTTF.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
                return sttsLinkSTTF;
            }).collect(Collectors.toList());
        this.sttcLinkSTTSRepository.deleteAllSTTCySTTSId(APPLICATION_STATUS.DELETE.getLookupValue(), payload.getSttsId(), adminUser.get().getAppUserId());
        this.sttsRepository.save(sttSectionOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTSection deleted with %d.", payload.getSttsId()));
    }

    /**
     * Method use to fetch STTS value by stts id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSBySttsId(STTSectionRequest payload) throws Exception {
        logger.info("Request fetchSTTSBySttsId :- " + payload);
        if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), payload.getAccessUserDetail().getUsername(),
            APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found.");
        }
        STTSectionResponse sttSectionResponse = new STTSectionResponse();
        sttSectionResponse.setSttsId(sttSectionOptional.get().getSttsId());
        sttSectionResponse.setSttsName(sttSectionOptional.get().getSttsName());
        sttSectionResponse.setDescription(sttSectionOptional.get().getDescription());
        sttSectionResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttSectionOptional.get().getStatus()));
        sttSectionResponse.setDefaultStts(ISDEFAULT.getDefaultByValue(sttSectionOptional.get().getDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", payload.getSttsId()), sttSectionResponse);
    }

    /**
     * Method use to fetch STTS value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTS(STTSectionRequest payload) throws Exception {
        logger.info("Request fetchSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSectionListResponse> result = this.sttsRepository.findByAppUserAndStatusNotIn(
            adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttsProjection -> {
                STTSectionListResponse sttsResponse = new STTSectionListResponse();
                sttsResponse.setSttsId(sttsProjection.getSttsId());
                sttsResponse.setSttsName(sttsProjection.getSttsName());
                sttsResponse.setDescription(sttsProjection.getDescription());
                sttsResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttsProjection.getStatus()));
                sttsResponse.setSttsDefault(ISDEFAULT.getDefaultByValue(sttsProjection.getSttsDefault()));
                sttsResponse.setDateCreated(sttsProjection.getDateCreated());
                sttsResponse.setTotalForm(this.sttsLinkSTTFRepository.countBySttsIdAndStatusNotIn(
                    sttsProjection.getSttsId(), APPLICATION_STATUS.DELETE.getLookupValue()));
                sttsResponse.setTotalControl(this.sttcLinkSTTSRepository.countBySttsIdAndStatusNotIn(
                    sttsProjection.getSttsId(), APPLICATION_STATUS.DELETE.getLookupValue()));
                return sttsResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTS Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request addSTTSLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTForm> sttFormOptional = this.sttfRepository.findBySttfIdAndAppUserAndStatusNotIn(
            payload.getSttfId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttFormOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTForm not found");
        }
        if (!this.sttsLinkSTTFRepository.findBySttsIdAndSttfIdAndAppUserIdAndStatus(payload.getSttsId(),
            payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
            STTSLinkSTTF sttsLinkSTTF = new STTSLinkSTTF();
            sttsLinkSTTF.setStts(sttSectionOptional.get());
            sttsLinkSTTF.setStatus(sttSectionOptional.get().getStatus());
            sttsLinkSTTF.setSttf(sttFormOptional.get());
            sttsLinkSTTF.setSttsOrder(payload.getSttsOrder());
            sttsLinkSTTF.setAppUser(adminUser.get());
            this.sttsLinkSTTFRepository.save(sttsLinkSTTF);
            return new AppResponse(ProcessUtil.SUCCESS, "STTSLinkSTTF successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF already exist.");
    }

    /**
     * Method use to delete STTS Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request deleteSTTSLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttfId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        } else if (isNull(payload.getAuSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSLinkSTTF> sttsLinkSTTFOptional = this.sttsLinkSTTFRepository.findByAuSttsIdAndSttsIdAndSttfIdAndAppUserIdAndStatusNotIn(
            payload.getAuSttsId(), payload.getSttsId(), payload.getSttfId(), adminUser.get().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttsLinkSTTFOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSLinkSTTF not found");
        }
        sttsLinkSTTFOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttsLinkSTTFRepository.save(sttsLinkSTTFOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTFLinkSTT successfully deleted.");
    }

    /**
     * Method use to fetch STTS Link STTF
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception {
        logger.info("Request fetchSTTSLinkSTTF :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSLinkSTTFListResponse> sttsLinkSTTFListResponses = this.sttsLinkSTTFRepository
            .findBySttsIdAndStatusNotIn(payload.getSttsId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttsLinkSTTF -> {
                STTSLinkSTTFListResponse sttsLinkSTTFListResponse = new STTSLinkSTTFListResponse();
                sttsLinkSTTFListResponse.setSttsLinkSttfId(sttsLinkSTTF.getAuSttsId());
                sttsLinkSTTFListResponse.setDateCreated(sttsLinkSTTF.getDateCreated());
                sttsLinkSTTFListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttsLinkSTTF.getStatus()));
                sttsLinkSTTFListResponse.setSttsOrder(sttsLinkSTTF.getSttsOrder());
                if (!ProcessUtil.isNull(sttsLinkSTTF.getSttf())) {
                    sttsLinkSTTFListResponse.setFormId(sttsLinkSTTF.getSttf().getSttfId());
                    sttsLinkSTTFListResponse.setFormName(sttsLinkSTTF.getSttf().getSttfName());
                    sttsLinkSTTFListResponse.setFormType(FORM_TYPE.getFormTypeByValue(sttsLinkSTTF.getSttf().getFormType()));
                    sttsLinkSTTFListResponse.setFormDefault(ISDEFAULT.getDefaultByValue(sttsLinkSTTF.getSttf().getDefault()));
                }
                return sttsLinkSTTFListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttsLinkSTTFListResponses);
    }

    /**
     * Method use to fetch STTS Link STTC
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request addSTTSLinkSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found");
        }
        if (!this.sttcLinkSTTSRepository.findBySttcIdAndAppUserIdAndSttsIdAndStatus(payload.getSttcId(),
            adminUser.get().getAppUserId(), payload.getSttsId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).isPresent()) {
            STTCLinkSTTS sttcLinkSTTS = new STTCLinkSTTS();
            sttcLinkSTTS.setSttc(sttControlOptional.get());
            sttcLinkSTTS.setSttcOrder(payload.getSttcOrder());
            sttcLinkSTTS.setStatus(sttSectionOptional.get().getStatus());
            sttcLinkSTTS.setStts(sttSectionOptional.get());
            sttcLinkSTTS.setAppUser(adminUser.get());
            this.sttcLinkSTTSRepository.save(sttcLinkSTTS);
            return new AppResponse(ProcessUtil.SUCCESS, "STTCLinkSTTS successfully linked.");
        }
        return new AppResponse(ProcessUtil.ERROR, "STTCLinkSTTS already exist.");
    }

    /**
     * Method use to delete STTS Link STTC
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request deleteSTTCLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(payload.getAuSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTCLinkSTTS> sttcLinkSTTSOptional = this.sttcLinkSTTSRepository.findByAuSttcIdAndSttsIdAndAppUserIdAndSttcIdAndStatusNotIn(
            payload.getAuSttcId(), payload.getSttsId(), adminUser.get().getAppUserId(), payload.getSttcId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttcLinkSTTSOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTCLinkSTTS not found");
        }
        sttcLinkSTTSOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttcLinkSTTSRepository.save(sttcLinkSTTSOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTSLinkSTTC successfully deleted.");
    }

    /**
     * Method use to fetch STTS Link STTC
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request fetchSTTSLinkSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTSLinkSTTCListResponse> sttsLinkSTTCListResponses = this.sttcLinkSTTSRepository
            .findBySttsIdAndStatusNotIn(payload.getSttsId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttsLinkSTTC -> {
                STTSLinkSTTCListResponse sttsLinkSTTCListResponse = new STTSLinkSTTCListResponse();
                sttsLinkSTTCListResponse.setSttsLinkSttcId(sttsLinkSTTC.getAuSttcId());
                sttsLinkSTTCListResponse.setDateCreated(sttsLinkSTTC.getDateCreated());
                sttsLinkSTTCListResponse.setSttcOrder(sttsLinkSTTC.getSttcOrder());
                sttsLinkSTTCListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttsLinkSTTC.getStatus()));
                if (!ProcessUtil.isNull(sttsLinkSTTC.getSttc())) {
                    sttsLinkSTTCListResponse.setSttcId(sttsLinkSTTC.getSttc().getSttcId());
                    sttsLinkSTTCListResponse.setSttcName(sttsLinkSTTC.getSttc().getSttcName());
                    sttsLinkSTTCListResponse.setFiledName(sttsLinkSTTC.getSttc().getFiledName());
                    sttsLinkSTTCListResponse.setFiledType(FORM_CONTROL_TYPE.getFormControlTypeByValue(sttsLinkSTTC.getSttc().getFiledType()));
                    sttsLinkSTTCListResponse.setMandatory(ISDEFAULT.getDefaultByValue(sttsLinkSTTC.getSttc().getMandatory()));
                    sttsLinkSTTCListResponse.setSttcDefault(ISDEFAULT.getDefaultByValue(sttsLinkSTTC.getSttc().getDefault()));
                    sttsLinkSTTCListResponse.setSttcDisabled(ISDEFAULT.getDefaultByValue(sttsLinkSTTC.getSttc().getDisabled()));
                }
                return sttsLinkSTTCListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttsLinkSTTCListResponses);
    }

    /**
     * Method use to add STTC value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTC(STTControlRequest payload) throws Exception {
        logger.info("Request addSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledType missing.");
        } else if (isNull(payload.getSttcName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCName missing.");
        } else if (isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(payload.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledName missing.");
        } else if (isNull(payload.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledTitle missing.");
        } else if (isNull(payload.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledWidth missing.");
        } else if (isNull(payload.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Mandatory missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        STTControl sttControl = new STTControl();
        sttControl.setSttcName(payload.getSttcName());
        sttControl.setFiledType(payload.getFiledType());
        sttControl.setFiledTitle(payload.getFiledTitle());
        sttControl.setFiledName(payload.getFiledName());
        sttControl.setDescription(payload.getDescription());
        sttControl.setPlaceHolder(payload.getPlaceHolder());
        sttControl.setFiledWidth(payload.getFiledWidth());
        sttControl.setMinLength(payload.getMinLength());
        sttControl.setMaxLength(payload.getMaxLength());
        sttControl.setDisabled(payload.isSttcDisabled());
        if (FORM_CONTROL_TYPE.RADIO.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.CHECKBOX.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.SELECT.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.MULTI_SELECT.getLookupValue().equals(payload.getFiledType())) {
            sttControl.setFiledLkValue(payload.getFiledLookUp());
        }
        sttControl.setMandatory(payload.isMandatory());
        sttControl.setDefault(ISDEFAULT.NO_DEFAULT.getLookupValue());
        sttControl.setDefaultValue(payload.getDefaultValue());
        sttControl.setPattern(payload.getPattern());
        sttControl.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        sttControl.setAppUser(adminUser.get());
        this.sttcRepository.save(sttControl);
        payload.setSttcId(sttControl.getSttcId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTControl added with %d.", payload.getSttcId()));
    }

    /**
     * Method use to edit STTC value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTC(STTControlRequest payload) throws Exception {
        logger.info("Request editSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(payload.getFiledType())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledType missing.");
        } else if (isNull(payload.getSttcName())) {
            return new AppResponse(ProcessUtil.ERROR, "SttCName missing.");
        } else if (isNull(payload.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Description missing.");
        } else if (isNull(payload.getFiledName())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledName missing.");
        } else if (isNull(payload.getFiledTitle())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledTitle missing.");
        } else if (isNull(payload.getFiledWidth())) {
            return new AppResponse(ProcessUtil.ERROR, "FiledWidth missing.");
        } else if (isNull(payload.isMandatory())) {
            return new AppResponse(ProcessUtil.ERROR, "Mandatory missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        if (FORM_CONTROL_TYPE.RADIO.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.CHECKBOX.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.SELECT.getLookupValue().equals(payload.getFiledType()) ||
            FORM_CONTROL_TYPE.MULTI_SELECT.getLookupValue().equals(payload.getFiledType())) {
            sttControlOptional.get().setFiledLkValue(payload.getFiledLookUp());
        }
        sttControlOptional.get().setSttcName(payload.getSttcName());
        sttControlOptional.get().setFiledType(payload.getFiledType());
        sttControlOptional.get().setFiledTitle(payload.getFiledTitle());
        sttControlOptional.get().setFiledName(payload.getFiledName());
        sttControlOptional.get().setDescription(payload.getDescription());
        sttControlOptional.get().setPlaceHolder(payload.getPlaceHolder());
        sttControlOptional.get().setFiledWidth(payload.getFiledWidth());
        sttControlOptional.get().setMinLength(payload.getMinLength());
        sttControlOptional.get().setMaxLength(payload.getMaxLength());
        sttControlOptional.get().setMandatory(payload.isMandatory());
        sttControlOptional.get().setDefault(payload.isSttcDefault());
        sttControlOptional.get().setDisabled(payload.isSttcDisabled());
        sttControlOptional.get().setDefaultValue(payload.getDefaultValue());
        sttControlOptional.get().setPattern(payload.getPattern());
        sttControlOptional.get().setStatus(payload.getStatus());
        sttControlOptional.get().setAppUser(adminUser.get());
        sttControlOptional.get().getSttcLink().stream()
            .filter(sttcLinkSTTS -> !sttcLinkSTTS.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(sttcLinkSTTS -> {
                sttcLinkSTTS.setStatus(payload.getStatus());
                return sttcLinkSTTS;
            }).collect(Collectors.toList());
        this.sttcRepository.save(sttControlOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTControl updated with %d.", payload.getSttcId()));
    }

    /**
     * Method use to delete STTC value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTC(STTControlRequest payload) throws Exception {
        logger.info("Request deleteSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        sttControlOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        sttControlOptional.get().getSttcLink().stream()
            .filter(sttcLinkSTTS -> !sttcLinkSTTS.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .map(sttcLinkSTTS -> {
                sttcLinkSTTS.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
                return sttcLinkSTTS;
            }).collect(Collectors.toList());
        this.sttcRepository.save(sttControlOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STTControl deleted with %d.", payload.getSttcId()));
    }

    /**
     * Method use to fetch STTC value by sttc id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCBySttcId(STTControlRequest payload) throws Exception {
        logger.info("Request fetchSTTCBySttcId :- " + payload);
        if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "Sttcid missing.");
        } else if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found.");
        }
        STTControlResponse sttControlResponse = new STTControlResponse();
        sttControlResponse.setSttcId(sttControlOptional.get().getSttcId());
        sttControlResponse.setSttcName(sttControlOptional.get().getSttcName());
        sttControlResponse.setDescription(sttControlOptional.get().getDescription());
        sttControlResponse.setFiledType(FORM_CONTROL_TYPE.getFormControlTypeByValue(sttControlOptional.get().getFiledType()));
        sttControlResponse.setFiledTitle(sttControlOptional.get().getFiledTitle());
        sttControlResponse.setFiledName(sttControlOptional.get().getFiledName());
        sttControlResponse.setPlaceHolder(sttControlOptional.get().getPlaceHolder());
        sttControlResponse.setFiledWidth(sttControlOptional.get().getFiledWidth());
        sttControlResponse.setMinLength(sttControlOptional.get().getMinLength());
        sttControlResponse.setMaxLength(sttControlOptional.get().getMaxLength());
        sttControlResponse.setFiledLookUp(sttControlOptional.get().getFiledLkValue());
        sttControlResponse.setMandatory(ISDEFAULT.getDefaultByValue(sttControlOptional.get().getMandatory()));
        sttControlResponse.setSttcDefault(ISDEFAULT.getDefaultByValue(sttControlOptional.get().getDefault()));
        sttControlResponse.setSttcDisabled(ISDEFAULT.getDefaultByValue(sttControlOptional.get().getDisabled()));
        sttControlResponse.setDefaultValue(sttControlOptional.get().getDefaultValue());
        sttControlResponse.setPattern(sttControlOptional.get().getPattern());
        sttControlResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttControlOptional.get().getStatus()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.", payload.getSttcId()), sttControlResponse);
    }

    /**
     * Method use to fetch STTC value
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTC(STTControlRequest payload) throws Exception {
        logger.info("Request fetchSTTC :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTControlListResponse> result = this.sttcRepository.findByAppUserUsernameAndStatusNotIn(
            adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttcProjection -> {
                STTControlListResponse sttControlListResponse = new STTControlListResponse();
                sttControlListResponse.setSttcId(sttcProjection.getSttcId());
                sttControlListResponse.setSttcName(sttcProjection.getSttcName());
                sttControlListResponse.setFiledName(sttcProjection.getFiledName());
                sttControlListResponse.setFiledType(FORM_CONTROL_TYPE.getFormControlTypeByValue(sttcProjection.getFiledType()));
                sttControlListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttcProjection.getStatus()));
                sttControlListResponse.setMandatory(ISDEFAULT.getDefaultByValue(sttcProjection.getMandatory()));
                sttControlListResponse.setSttcDefault(ISDEFAULT.getDefaultByValue(sttcProjection.getSttcDefault()));
                sttControlListResponse.setDateCreated(sttcProjection.getDateCreated());
                sttControlListResponse.setTotalSection(this.sttcLinkSTTSRepository.countBySttcIdAndStatusNotIn(
                    sttcProjection.getSttcId(), APPLICATION_STATUS.DELETE.getLookupValue()));
                return sttControlListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    /**
     * Method use to STTC Link STTS
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request addSTTCLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttfId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTSection> sttSectionOptional = this.sttsRepository.findBySttsIdAndAppUserAndStatusNotIn(
            payload.getSttsId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttSectionOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTSection not found");
        }
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found");
        }
        if (!this.sttcLinkSTTSRepository.findBySttcIdAndAppUserIdAndSttsIdAndStatus(payload.getSttcId(),
            adminUser.get().getAppUserId(), payload.getSttsId(), APPLICATION_STATUS.ACTIVE.getLookupValue())
            .isPresent()) {
            STTCLinkSTTS sttcLinkSTTS = new STTCLinkSTTS();
            sttcLinkSTTS.setSttc(sttControlOptional.get());
            sttcLinkSTTS.setSttcOrder(payload.getSttcOrder());
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
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request deleteSTTCLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttsId missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(payload.getAuSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuSttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        Optional<STTCLinkSTTS> sttcLinkSTTSOptional = this.sttcLinkSTTSRepository.findByAuSttcIdAndSttsIdAndAppUserIdAndSttcIdAndStatusNotIn(
            payload.getAuSttcId(), payload.getSttsId(), adminUser.get().getAppUserId(), payload.getSttcId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttcLinkSTTSOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTCLinkSTTS not found");
        }
        sttcLinkSTTSOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.sttcLinkSTTSRepository.save(sttcLinkSTTSOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "STTCLinkSTTS successfully deleted.");
    }

    /**
     * Method use to fetch STTC Link STTS
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception {
        logger.info("Request fetchSTTCLinkSTTS :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<STTCLinkSTTSListResponse> sttcLinkSTTSListResponses = this.sttcLinkSTTSRepository
            .findBySttcIdAndStatusNotIn(payload.getSttcId(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(sttcLinkSTTS -> {
                STTCLinkSTTSListResponse sttcLinkSTTSListResponse = new STTCLinkSTTSListResponse();
                sttcLinkSTTSListResponse.setSttcLinkSttsId(sttcLinkSTTS.getAuSttcId());
                sttcLinkSTTSListResponse.setDateCreated(sttcLinkSTTS.getDateCreated());
                sttcLinkSTTSListResponse.setSttcOrder(sttcLinkSTTS.getSttcOrder());
                sttcLinkSTTSListResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttcLinkSTTS.getStatus()));
                if (!ProcessUtil.isNull(sttcLinkSTTS.getStts())) {
                    sttcLinkSTTSListResponse.setSttsId(sttcLinkSTTS.getStts().getSttsId());
                    sttcLinkSTTSListResponse.setSttsName(sttcLinkSTTS.getStts().getSttsName());
                    sttcLinkSTTSListResponse.setDescription(sttcLinkSTTS.getStts().getDescription());
                    sttcLinkSTTSListResponse.setSttsDefault(ISDEFAULT.getDefaultByValue(sttcLinkSTTS.getStts().getDefault()));
                }
                return sttcLinkSTTSListResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", sttcLinkSTTSListResponses);
    }

    @Override
    public AppResponse fetchSTTFormDetail(Long formId) throws Exception {
        logger.info("Request fetchSTTFormDetail :- " + formId);
        List<STTSLinkSTTF> sttsLinkSTTFList = this.sttsLinkSTTFRepository.findBySttfIdAndStatusNotIn(formId, APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttsLinkSTTFList.isEmpty()) {
            Collections.sort(sttsLinkSTTFList, Comparator.comparingLong(STTSLinkSTTF ::getSttsOrder));
            FormSectionResponse formResponse = new FormSectionResponse();
            formResponse.setFormDetail(this.getSTTFormResponse(sttsLinkSTTFList.get(0).getSttf()));
            formResponse.setFormSection(
                sttsLinkSTTFList.stream().map(sttsLinkSTTF -> {
                    SectionControlResponse sectionResponse = new SectionControlResponse();
                    sectionResponse.setAuSttsId(sttsLinkSTTF.getAuSttsId());
                    sectionResponse.setSectionOder(sttsLinkSTTF.getSttsOrder());
                    sectionResponse.setSection(getSTTSectionResponse(sttsLinkSTTF.getStts()));
                    List<STTCLinkSTTS> sttcLinkSTTSList = this.sttcLinkSTTSRepository.findBySttsIdAndStatusNotIn(
                        sttsLinkSTTF.getStts().getSttsId(), APPLICATION_STATUS.DELETE.getLookupValue());
                    if (!sttcLinkSTTSList.isEmpty()) {
                        Collections.sort(sttcLinkSTTSList, Comparator.comparingLong(STTCLinkSTTS ::getSttcOrder));
                        sectionResponse.setControlFiled(
                            sttcLinkSTTSList.stream().map(sttcLinkSTTS -> {
                                STTControlResponse sttControlResponse = this.getSTTControlResponse(sttcLinkSTTS.getSttc());
                                sttControlResponse.setControlOrder(sttcLinkSTTS.getSttcOrder());
                                sttControlResponse.setInteraction(this.getSTTCInteractionsResponse(sttsLinkSTTF.getAuSttsId(), sttcLinkSTTS.getSttc().getSttcId()));
                                return sttControlResponse;
                            }).collect(Collectors.toList()));
                    }
                    return sectionResponse;
                }).collect(Collectors.toList())
            );
            return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", formResponse);
        }
        return new AppResponse(ProcessUtil.ERROR, "No data found.");
    }

    /**
     * Method use to link the sttc with interaction
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTCInteractions(STTCInteractionsRequest payload) throws Exception {
        logger.info("Request addSTTCInteractions :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (isNull(payload.getSttcId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        } else if (isNull(payload.getAuSttsId())) {
            return new AppResponse(ProcessUtil.ERROR, "auSttsId missing.");
        } else if (isNull(payload.getDisabledPattern()) && isNull(payload.getVisiblePattern())) {
            return new AppResponse(ProcessUtil.ERROR, isNull(payload.getDisabledPattern()) ?  "disabledPattern missing." : "visiblePattern missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        STTCInteractions sttcInteractions = (!isNull(payload.getInteractionsId())) ?
                this.sttcInteractionsRepository.findByInteractionsIdAndStatusNot(payload.getInteractionsId(),
                    APPLICATION_STATUS.DELETE.getLookupValue()).orElseThrow() : new STTCInteractions();
        sttcInteractions.setAppUser(adminUser.get());
        sttcInteractions.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        Optional<STTControl> sttControlOptional = this.sttcRepository.findBySttcIdAndAppUserUsernameAndStatusNotIn(
            payload.getSttcId(), adminUser.get().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttControlOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "STTControl not found");
        }
        Optional<STTSLinkSTTF> sttsLinkSTTF = this.sttsLinkSTTFRepository.findByAuSttsIdAndAppUserIdAndStatusNotIn(
            payload.getAuSttsId(), payload.getAccessUserDetail().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttsLinkSTTF.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "SttsLinkSttf not found");
        }
        sttcInteractions.setSttsLinkSTTF(sttsLinkSTTF.get());
        sttcInteractions.setDisabledPattern(payload.getDisabledPattern());
        sttcInteractions.setVisiblePattern(payload.getVisiblePattern());
        sttcInteractions.setSttc(sttControlOptional.get());
        this.sttcInteractionsRepository.save(sttcInteractions);
        payload.setInteractionsId(sttcInteractions.getInteractionsId());
        return new AppResponse(ProcessUtil.SUCCESS, "Data saved.", payload);
    }

    /**
     * Method use to dlink the sttc with interaction
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteSTTCInteractions(STTCInteractionsRequest payload) throws Exception {
        logger.info("Request deleteSTTCInteractions :- " + payload);
        if (isNull(payload.getInteractionsId())) {
            return new AppResponse(ProcessUtil.ERROR, "SttcId missing.");
        }
        Optional<STTCInteractions> sttcInteractions = this.sttcInteractionsRepository.findByInteractionsIdAndStatusNot(
            payload.getInteractionsId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!sttcInteractions.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "No data found.");
        }
        sttcInteractions.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        return new AppResponse(ProcessUtil.SUCCESS, "Sttc Interaction deleted.");
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
        XSSFSheet sheet = workbook.createSheet(sheetName);
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
            sttFileUReq.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            throw new Exception("AppUser not found");
        }
        if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT)) {
            this.sttRepository.findSttByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                APPLICATION_STATUS.DELETE.getLookupValue()).forEach(stt -> {
                    if (TASKTYPE_OPTION.KAFKA.getLookupValue().equals(stt.getTaskType())) {
                        rowCount.getAndIncrement();
                        List<String> dataCellValue = new ArrayList<>();
                        dataCellValue.add(stt.getServiceName());
                        dataCellValue.add(stt.getDescription());
                        dataCellValue.add(ISDEFAULT.getDefaultByValue(stt.getDefault()).getDescription());
                        dataCellValue.add(TASKTYPE_OPTION.getTaskTypeByValue(stt.getTaskType()).getDescription());
                        // only kafka support for this version 2.0
                        dataCellValue.add(stt.getKafkaTaskType().get(0).getTopicName());
                        dataCellValue.add(stt.getKafkaTaskType().get(0).getNumPartitions().toString());
                        this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                    }
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_FORM)) {
            this.sttfRepository.findByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                APPLICATION_STATUS.DELETE.getLookupValue()).forEach(sttf -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(sttf.getSttfName());
                    dataCellValue.add(sttf.getDescription());
                    dataCellValue.add(ISDEFAULT.getDefaultByValue(sttf.getSttFDefault()).getDescription());
                    dataCellValue.add(FORM_TYPE.getFormTypeByValue(sttf.getFormType()).getDescription());
                    this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_SECTION)) {
            this.sttsRepository.findByAppUserAndStatusNotIn(adminUser.get().getUsername(),
                APPLICATION_STATUS.DELETE.getLookupValue()).forEach(stts -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(stts.getSttsName());
                    dataCellValue.add(stts.getDescription());
                    dataCellValue.add(ISDEFAULT.getDefaultByValue(stts.getSttsDefault()).getDescription());
                    this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
                });
        } else if (sttFileUReq.getDownloadType().equals(this.bulkExcel.STT_CONTROL)) {
            this.sttcRepository.findSttControlByAppUserUsernameAndStatusNotIn(adminUser.get().getUsername(),
                APPLICATION_STATUS.DELETE.getLookupValue()).forEach(sttc -> {
                    rowCount.getAndIncrement();
                    List<String> dataCellValue = new ArrayList<>();
                    dataCellValue.add(sttc.getSttcName());
                    dataCellValue.add(sttc.getDescription());
                    dataCellValue.add(sttc.getFiledName());
                    dataCellValue.add(sttc.getFiledTitle());
                    dataCellValue.add(!ProcessUtil.isNull(sttc.getFiledWidth()) ?
                        sttc.getFiledWidth().toString(): this.bulkExcel.BLANK_VAL);
                    dataCellValue.add(sttc.getPlaceHolder());
                    dataCellValue.add(sttc.getPattern());
                    dataCellValue.add(FORM_CONTROL_TYPE.getFormControlTypeByValue(
                        sttc.getFiledType()).getDescription());
                    dataCellValue.add(!ProcessUtil.isNull(sttc.getMinLength()) ?
                        sttc.getMinLength().toString(): this.bulkExcel.BLANK_VAL);
                    dataCellValue.add(!ProcessUtil.isNull(sttc.getMaxLength()) ?
                        sttc.getMaxLength().toString(): this.bulkExcel.BLANK_VAL);
                    dataCellValue.add(ISDEFAULT.getDefaultByValue(
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
            sttFileUReq.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
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
            stt.setTaskType(Long.valueOf(TASKTYPE_OPTION.getTaskTypeByDescription(
                sttValidation.getTaskType()).getLookupValue().toString()));
            stt.setDefault(Boolean.valueOf(ISDEFAULT.getDefaultByDescription(
                sttValidation.getDefaultSTT()).getLookupValue().toString()));
            stt.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
            if (stt.getTaskType().equals(TASKTYPE_OPTION.KAFKA.getLookupValue())) {
                KafkaTaskType kafkaTaskType = new KafkaTaskType();
                kafkaTaskType.setNumPartitions(Integer.valueOf(sttValidation.getPartitions()));
                kafkaTaskType.setTopicName(sttValidation.getTopicName());
                kafkaTaskType.setTopicPattern(sttValidation.getTopicPattern());
                kafkaTaskType.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
                stt.setAppUser(appUser);
                stt = this.sttRepository.save(stt);
                kafkaTaskType.setStt(stt);
                this.kafkaTaskTypeRepository.save(kafkaTaskType);
            }
            AppUserSTT appUserSTT = new AppUserSTT();
            appUserSTT.setStt(stt);
            appUserSTT.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
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
            sttForm.setDefault(Boolean.valueOf(ISDEFAULT.getDefaultByDescription(
                sttfValidation.getDefaultSTTF()).getLookupValue().toString()));
            sttForm.setFormType(Long.valueOf(FORM_TYPE.getFormTypeByDescription(
                sttfValidation.getFormType()).getLookupValue().toString()));
            sttForm.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
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
            return new AppResponse(ProcessUtil.ERROR, String.format(
                "Total %d STTS invalid.", errors.size()), errors);
        }
        sttsValidations.forEach(sttsValidation -> {
            STTSection sttSection = new STTSection();
            sttSection.setSttsName(sttsValidation.getSectionName());
            sttSection.setDescription(sttsValidation.getDescription());
            sttSection.setDefault(Boolean.valueOf(ISDEFAULT.getDefaultByDescription(
                sttsValidation.getDefaultSTTS()).getLookupValue().toString()));
            sttSection.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
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
            sttControl.setSttcName(sttcValidation.getControlName());
            sttControl.setDescription(sttcValidation.getDescription());
            sttControl.setFiledType(FORM_CONTROL_TYPE.getFormControlTypeByDescription(
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
            if (FORM_CONTROL_TYPE.RADIO.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FORM_CONTROL_TYPE.CHECKBOX.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FORM_CONTROL_TYPE.SELECT.getLookupValue().equals(sttcValidation.getFiledType()) ||
                FORM_CONTROL_TYPE.MULTI_SELECT.getLookupValue().equals(sttcValidation.getFiledType())) {
                sttControl.setFiledLkValue(sttcValidation.getFiledLkValue());
            }
            sttControl.setMandatory(Boolean.valueOf(ISDEFAULT.getDefaultByDescription(
                sttcValidation.getRequired()).getLookupValue().toString()));
            sttControl.setDefault(ISDEFAULT.NO_DEFAULT.getLookupValue());
            sttControl.setDisabled(ISDEFAULT.NO_DEFAULT.getLookupValue());
            sttControl.setPattern(sttcValidation.getPattern());
            sttControl.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
            sttControl.setAppUser(appUser);
            this.sttcRepository.save(sttControl);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

    /**
     * Method use get the form detail
     * @param sttForm
     * */
    private STTFormResponse getSTTFormResponse(STTForm sttForm) {
        STTFormResponse sttFormResponse = new STTFormResponse();
        sttFormResponse.setSttfId(sttForm.getSttfId());
        sttFormResponse.setSttfName(sttForm.getSttfName());
        sttFormResponse.setDescription(sttForm.getDescription());
        sttFormResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttForm.getStatus()));
        sttFormResponse.setFormType(FORM_TYPE.getFormTypeByValue(sttForm.getFormType()));
        if (!ProcessUtil.isNull(sttForm.getHomePage())) {
            sttFormResponse.setHomePage(this.getDBLoopUp(this.lookupDataRepository.findByLookupType(sttForm.getHomePage())));
        }
        sttFormResponse.setServiceId(sttForm.getServiceId());
        sttFormResponse.setDefaultSttf(ISDEFAULT.getDefaultByValue(sttForm.getDefault()));
        return sttFormResponse;
    }

    /**
     * Method use sttSection to STTSectionResponse
     * @param sttSection
     * */
    private STTSectionResponse getSTTSectionResponse(STTSection sttSection) {
        STTSectionResponse sectionResponse = new STTSectionResponse();
        sectionResponse.setSttsId(sttSection.getSttsId());
        sectionResponse.setSttsName(sttSection.getSttsName());
        sectionResponse.setDescription(sttSection.getDescription());
        return sectionResponse;
    }

    /**
     * Method use to convert sttControl to STTControlResponse
     * @param sttControl
     * */
    private STTControlResponse getSTTControlResponse(STTControl sttControl) {
        STTControlResponse sttControlResponse = new STTControlResponse();
        sttControlResponse.setSttcId(sttControl.getSttcId());
        sttControlResponse.setSttcName(sttControl.getSttcName());
        sttControlResponse.setDescription(sttControl.getDescription());
        sttControlResponse.setFiledType(FORM_CONTROL_TYPE.getFormControlTypeByValue(sttControl.getFiledType()));
        sttControlResponse.setFiledTitle(sttControl.getFiledTitle());
        sttControlResponse.setFiledName(sttControl.getFiledName());
        sttControlResponse.setPlaceHolder(sttControl.getPlaceHolder());
        sttControlResponse.setFiledWidth(sttControl.getFiledWidth());
        sttControlResponse.setMinLength(sttControl.getMinLength());
        sttControlResponse.setMaxLength(sttControl.getMaxLength());
        sttControlResponse.setFiledLookUp(sttControl.getFiledLkValue());
        sttControlResponse.setMandatory(ISDEFAULT.getDefaultByValue(sttControl.getMandatory()));
        sttControlResponse.setSttcDefault(ISDEFAULT.getDefaultByValue(sttControl.getDefault()));
        sttControlResponse.setSttcDisabled(ISDEFAULT.getDefaultByValue(sttControl.getDisabled()));
        sttControlResponse.setDefaultValue(sttControl.getDefaultValue());
        sttControlResponse.setPattern(sttControl.getPattern());
        sttControlResponse.setStatus(APPLICATION_STATUS.getStatusByValue(sttControl.getStatus()));
        return sttControlResponse;
    }

    /**
     * Method use get the interaction for control link with section and form
     * @param auSttsId
     * @param sttcId
     * */
    private STTCInteractionsResponse getSTTCInteractionsResponse(Long auSttsId, Long sttcId) {
        Optional<STTCInteractions> sttcInteractions = this.sttcInteractionsRepository.findByAuSttsIdAndSttcIdAndStatusNotIn(
            auSttsId, sttcId, APPLICATION_STATUS.DELETE.getLookupValue());
        if (sttcInteractions.isPresent()) {
            STTCInteractionsResponse sttcInteractionsResponse = new STTCInteractionsResponse();
            sttcInteractionsResponse.setInteractionsId(sttcInteractions.get().getInteractionsId());
            sttcInteractionsResponse.setDisabledPattern(sttcInteractions.get().getDisabledPattern());
            sttcInteractionsResponse.setVisiblePattern(sttcInteractions.get().getVisiblePattern());
            return sttcInteractionsResponse;
        }
        return null;
    }
}
