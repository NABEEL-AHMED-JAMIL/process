package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.pojo.*;
import process.model.repository.*;
import process.payload.request.*;
import process.payload.response.*;
import process.service.LookupDataCacheService;
import process.service.SourceTaskTypeService;
import process.util.ProcessUtil;
import process.util.lookuputil.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceTaskTypeServiceImpl implements SourceTaskTypeService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskTypeServiceImpl.class);

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
    private STTFormRepository sttFormRepository;
    @Autowired
    private STTSectionRepository sttSectionRepository;

    /**
     * Method use to add STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTT(STTRequest sttRequest) {
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
        appUser.get().addAppUserSourceTaskTypes(stt);
        this.appUserRepository.save(appUser.get());
        sttRequest.setSttId(stt.getSttId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.", sttRequest.getSttId()));
    }

    /**
     * Method use to edit STT value in kafka|api etc.
     * @param sttRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTT(STTRequest sttRequest) {
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
        Optional<STT> sourceTaskType = this.sttRepository.findBySttIdAndAppUserUsername(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        sourceTaskType.get().setServiceName(sttRequest.getServiceName());
        sourceTaskType.get().setDescription(sttRequest.getDescription());
        sourceTaskType.get().setTaskType(sttRequest.getTaskType());
        sourceTaskType.get().setDefault(sttRequest.isDefaultStt());
        if (!ProcessUtil.isNull(sttRequest.getStatus())) {
            sourceTaskType.get().setStatus(sttRequest.getStatus());
        }
        // if TaskType change not allow to update stt
        if (!sourceTaskType.get().getTaskType().equals(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType cannot change to different taskType.");
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

    @Override
    public AppResponse deleteSTT(STTRequest sttRequest) {
        return null;
    }

    @Override
    public AppResponse viewSTT() {
        return null;
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
        Optional<STT> sourceTaskType = this.sttRepository.findBySttIdAndAppUserUsername(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        SttResponse sttResponse = new SttResponse();
        sttResponse.setSttId(sourceTaskType.get().getSttId());
        sttResponse.setServiceName(sourceTaskType.get().getServiceName());
        sttResponse.setDescription(sourceTaskType.get().getDescription());
        sttResponse.setStatus(Status.getStatusByValue(sourceTaskType.get().getStatus()));
        sttResponse.setTaskType(TaskType.getTaskTypeByValue(sourceTaskType.get().getTaskType()));
        sttResponse.setDefaultStt(IsDefault.getDefaultByValue(sourceTaskType.get().isDefault()));
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
            apiTaskTypeResponse.setHttpMethod(RequestMethod.getRequestMethodByValue(apiTaskType.getHttpMethod().ordinal()));
            apiTaskTypeResponse.setApiSecurityLkValue(apiTaskType.getApiSecurityLkValue());
            sttResponse.setApiTaskType(apiTaskTypeResponse);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Data fetch successfully with %d.",
            sttRequest.getSttId()), sttResponse);
    }

    @Override
    public AppResponse fetchSTT(STTRequest sttRequest) {
        logger.info("Request fetchSTT :- " + sttRequest);
        if (isNull(sttRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<SttListResponse> result = this.sttRepository.findByAppUserUsername(appUser.get().getUsername())
        .stream().map(sttProjection -> {
            SttListResponse sttResponse = new SttListResponse();
            sttResponse.setSttId(sttProjection.getSttId());
            sttResponse.setServiceName(sttProjection.getServiceName());
            sttResponse.setDescription(sttProjection.getDescription());
            sttResponse.setStatus(Status.getStatusByValue(sttProjection.getStatus()));
            sttResponse.setTaskType(TaskType.getTaskTypeByValue(sttProjection.getTaskType()));
            sttResponse.setDefault(sttProjection.getSttDefault());
            sttResponse.setDateCreated(sttProjection.getDateCreated());
            sttResponse.setTotalUser(sttProjection.getTotalUser());
            sttResponse.setTotalTask(sttProjection.getTotalTask());
            sttResponse.setTotalForm(sttProjection.getTotalForm());
          return sttResponse;
        }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    @Override
    public AppResponse downloadSTTTree() {
        return null;
    }

    @Override
    public AppResponse linkSTTWithFrom() {
        return null;
    }

    /**
     * Method use to add STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse addSTTF(STTFormRequest sttFormRequest) {
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
        }
        STTForm sttForm = new STTForm();
        sttForm.setSttFName(sttFormRequest.getSttfName());
        sttForm.setDescription(sttFormRequest.getDescription());
        sttForm.setDefault(sttFormRequest.isDefaultSttf());
        sttForm.setStatus(Status.ACTIVE.getLookupValue());
        sttForm.setAppUser(appUser.get());
        sttForm = this.sttFormRepository.save(sttForm);
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Sttf save with %d.", sttForm.getSttFId()));
    }

    /**
     * Method use to edit STTF value
     * @param sttFormRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse editSTTF(STTFormRequest sttFormRequest) {
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
        }
        Optional<STTForm> sttForm = this.sttFormRepository.findBySttFIdAndAppUserUsername(
            sttFormRequest.getSttfId(), sttFormRequest.getAccessUserDetail().getUsername());
        sttForm.get().setSttFName(sttFormRequest.getSttfName());
        sttForm.get().setDescription(sttFormRequest.getDescription());
        sttForm.get().setDefault(sttFormRequest.isDefaultSttf());
        if (!ProcessUtil.isNull(sttFormRequest.getStatus())) {
            sttForm.get().setStatus(sttFormRequest.getStatus());
        }
        this.sttFormRepository.save(sttForm.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Sttf updated with %d.", sttFormRequest.getSttfId()));
    }

    @Override
    public AppResponse deleteSTTF(STTFormRequest sttFormRequest) {
        return null;
    }

    @Override
    public AppResponse viewSTTF() {
        return null;
    }

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
        Optional<STTForm> sttForm = this.sttFormRepository.findBySttFIdAndAppUserUsername(
            sttFormRequest.getSttfId(), sttFormRequest.getAccessUserDetail().getUsername());
        if (!sttForm.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Sttf not found.");
        }
        SttfResponse sttfResponse = new SttfResponse();
        sttfResponse.setSttFId(sttForm.get().getSttFId());
        sttfResponse.setSttFName(sttForm.get().getSttFName());
        sttfResponse.setDescription(sttForm.get().getDescription());
        sttfResponse.setStatus(Status.getStatusByValue(sttForm.get().getStatus()));
        sttfResponse.setDefaultSttf(IsDefault.getDefaultByValue(sttForm.get().isDefault()));
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Data fetch successfully with %d.", sttFormRequest.getSttfId()), sttfResponse);
    }

    @Override
    public AppResponse fetchSTTF(STTFormRequest sttFormRequest) {
        logger.info("Request fetchSTTF :- " + sttFormRequest);
        if (isNull(sttFormRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            sttFormRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<SttfListResponse> result = this.sttFormRepository.findByAppUserUsername(appUser.get().getUsername())
            .stream().map(sttfProjection -> {
                SttfListResponse sttfResponse = new SttfListResponse();
                sttfResponse.setSttFId(sttfProjection.getSttFId());
                sttfResponse.setSttFName(sttfProjection.getSttFName());
                sttfResponse.setDescription(sttfProjection.getDescription());
                sttfResponse.setStatus(Status.getStatusByValue(sttfProjection.getStatus()));
                sttfResponse.setDefault(sttfProjection.getSttFDefault());
                sttfResponse.setDateCreated(sttfProjection.getDateCreated());
                sttfResponse.setTotalStt(sttfResponse.getTotalStt());
                sttfResponse.setTotalSection(sttfResponse.getTotalSection());
                sttfResponse.setTotalControl(sttfResponse.getTotalControl());
                return sttfResponse;
            }).collect(Collectors.toList());
        return new AppResponse(ProcessUtil.SUCCESS, "Data fetch successfully", result);
    }

    @Override
    public AppResponse downloadSTTFTree() {
        return null;
    }

    @Override
    public AppResponse linkSTTFWithFrom() {
        return null;
    }

    @Override
    public AppResponse addSTTS(STTSectionRequest sttSectionRequest) {
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
        sttSection.setStatus(Status.ACTIVE.getLookupValue());
        sttSection.setAppUser(appUser.get());
        sttSection.setSttSName(sttSectionRequest.getSttsName());
        sttSection.setSttSOrder(sttSectionRequest.getSttsOrder());
        sttSection.setDescription(sttSectionRequest.getDescription());
        sttSection = this.sttSectionRepository.save(sttSection);
        sttSectionRequest.setSttsId(sttSection.getSttSId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Stts added with %d.", sttSectionRequest.getSttsId()));
    }

    @Override
    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) {
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
        Optional<STTSection> sttSection = this.sttSectionRepository.findBySttSIdAndAppUserUsername(
            sttSectionRequest.getSttsId(), sttSectionRequest.getAccessUserDetail().getUsername());
        sttSection.get().setStatus(sttSectionRequest.getStatus());
        sttSection.get().setAppUser(appUser.get());
        sttSection.get().setSttSName(sttSectionRequest.getSttsName());
        sttSection.get().setSttSOrder(sttSectionRequest.getSttsOrder());
        sttSection.get().setDescription(sttSectionRequest.getDescription());
        sttSection.get().setDefault(sttSectionRequest.isDefaultStts());
        this.sttSectionRepository.save(sttSection.get());
        sttSectionRequest.setSttsId(sttSectionRequest.getSttsId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Stts update with %d.", sttSectionRequest.getSttsId()));
    }

    @Override
    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest) {
        return null;
    }

    @Override
    public AppResponse viewSTTS() {
        return null;
    }

    @Override
    public AppResponse fetchSTTS() {
        return null;
    }

    @Override
    public AppResponse downloadSTTSTree() {
        return null;
    }

    @Override
    public AppResponse linkSTTSWithFrom() {
        return null;
    }

    @Override
    public AppResponse addSTTC(STTControl sttControl) {
        return null;
    }

    @Override
    public AppResponse editSTTC(STTControl sttControl) {
        return null;
    }

    @Override
    public AppResponse deleteSTTC(STTControl sttControl) {
        return null;
    }

    @Override
    public AppResponse fetchSTTC() {
        return null;
    }

    @Override
    public AppResponse downloadSTTCTree() {
        return null;
    }

    @Override
    public AppResponse linkSTTCWithFrom() {
        return null;
    }

    @Override
    public AppResponse downloadSTTCommonTemplateFile() {
        return null;
    }

    @Override
    public AppResponse downloadSTTCommon() {
        return null;
    }

    @Override
    public AppResponse uploadSTTCommon(FileUploadRequest fileObject) {
        return null;
    }

}
