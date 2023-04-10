package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.enums.Status;
import process.model.enums.TaskType;
import process.model.pojo.*;
import process.model.repository.*;
import process.payload.request.*;
import process.payload.response.AppResponse;
import process.service.LookupDataCacheService;
import process.service.SourceTaskTypeService;
import process.util.CommonUtil;
import process.util.ProcessUtil;
import java.util.Optional;
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
    private SourceTaskTypeRepository sourceTaskTypeRepository;
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
            sttRequest.getAccessUserDetail().getUsername(), Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
        }else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.API) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        }
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setServiceName(sttRequest.getServiceName());
        sourceTaskType.setDescription(sttRequest.getDescription());
        sourceTaskType.setTaskType(sttRequest.getTaskType());
        sourceTaskType.setDefault(sttRequest.isDefault());
        sourceTaskType.setStatus(Status.Active);
        if (sttRequest.getTaskType().equals(TaskType.API)) {
             ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(this.lookupDataCacheService
                .getParentLookupById(CommonUtil.LookupDetail.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url invalid.");
            }
            ApiTaskType apiTaskType = new ApiTaskType();
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            apiTaskType.setApiSecurityIdMlu(apiTaskTypeRequest.getApiSecurityIdMlu());
            apiTaskType.setApiSecurityIdSlu(apiTaskTypeRequest.getApiSecurityIdSlu());
            apiTaskType.setStatus(Status.Active);
            sourceTaskType.setAppUser(appUser.get());
            sourceTaskType = this.sourceTaskTypeRepository.save(sourceTaskType);
            apiTaskType.setSourceTaskType(sourceTaskType);
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA)) {
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
            sourceTaskType.setAppUser(appUser.get());
            kafkaTaskType.setStatus(Status.Active);
            sourceTaskType = this.sourceTaskTypeRepository.save(sourceTaskType);
            kafkaTaskType.setSourceTaskType(sourceTaskType);
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        sttRequest.setSttId(sourceTaskType.getSourceTaskTypeId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.",sttRequest.getSttId()));
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
            sttRequest.getAccessUserDetail().getUsername(), Status.Active);
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
        } else if (sttRequest.getTaskType().equals(TaskType.API) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType with Api type missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findBySourceTaskTypeIdAndAppUserUsername(
            sttRequest.getSttId(), sttRequest.getAccessUserDetail().getUsername());
        if (!sourceTaskType.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Stt not found.");
        }
        sourceTaskType.get().setServiceName(sttRequest.getServiceName());
        sourceTaskType.get().setDescription(sttRequest.getDescription());
        sourceTaskType.get().setTaskType(sttRequest.getTaskType());
        sourceTaskType.get().setDefault(sttRequest.isDefault());
        if (!ProcessUtil.isNull(sttRequest.getStatus())) {
            sourceTaskType.get().setStatus(sttRequest.getStatus());
        }
        // if TaskType change not allow to update stt
        if (!sourceTaskType.get().getTaskType().equals(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType cannot change to different taskType.");
        }
        if (sttRequest.getTaskType().equals(TaskType.API)) {
            ApiTaskTypeRequest apiTaskTypeRequest = sttRequest.getApiTaskType();
            if (ProcessUtil.isNull(apiTaskTypeRequest.getApiUrl())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url missing.");
            } else if (ProcessUtil.isNull(apiTaskTypeRequest.getHttpMethod())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt http method missing.");
            } else if (!apiTaskTypeRequest.getApiUrl().matches(this.lookupDataCacheService
                    .getParentLookupById(CommonUtil.LookupDetail.URL_VALIDATOR).getLookupValue())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt api url invalid.");
            }
            ApiTaskType apiTaskType = sourceTaskType.get().getApiTaskType().get(0);
            apiTaskType.setApiUrl(apiTaskTypeRequest.getApiUrl());
            apiTaskType.setHttpMethod(apiTaskTypeRequest.getHttpMethod());
            apiTaskType.setApiSecurityIdMlu(apiTaskTypeRequest.getApiSecurityIdMlu());
            apiTaskType.setApiSecurityIdSlu(apiTaskTypeRequest.getApiSecurityIdSlu());
            // give the same status of parent type
            if (!ProcessUtil.isNull(sttRequest.getStatus())) {
                apiTaskType.setStatus(sttRequest.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            this.apiTaskTypeRepository.save(apiTaskType);
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA)) {
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
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("STT save with %d.",sttRequest.getSttId()));
    }

    @Override
    public AppResponse deleteSTT(STTRequest sttRequest) {
        return null;
    }

    @Override
    public AppResponse viewSTT() {
        return null;
    }

    @Override
    public AppResponse fetchSTT() {
        return null;
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
            sttFormRequest.getAccessUserDetail().getUsername(), Status.Active);
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
        sttForm.setDefault(sttFormRequest.isDefault());
        sttForm.setStatus(Status.Active);
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
            sttFormRequest.getAccessUserDetail().getUsername(), Status.Active);
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
        sttForm.get().setDefault(sttFormRequest.isDefault());
        sttForm.get().setStatus(sttFormRequest.getStatus());
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
    public AppResponse fetchSTTF() {
        return null;
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
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.Active);
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
        sttSection.setStatus(Status.Active);
        sttSection.setAppUser(appUser.get());
        sttSection.setSttSName(sttSectionRequest.getSttsName());
        sttSection.setSttSOrder(sttSectionRequest.getSttsOrder());
        sttSection.setDescription(sttSectionRequest.getDescription());
        sttSection.setDefault(sttSectionRequest.isDefault());
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
            sttSectionRequest.getAccessUserDetail().getUsername(), Status.Active);
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
        sttSection.get().setDefault(sttSectionRequest.isDefault());
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
