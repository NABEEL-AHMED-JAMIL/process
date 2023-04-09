package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.enums.Status;
import process.model.enums.TaskType;
import process.model.pojo.*;
import process.model.repository.ApiTaskTypeRepository;
import process.model.repository.AppUserRepository;
import process.model.repository.KafkaTaskTypeRepository;
import process.model.repository.SourceTaskTypeRepository;
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
        }
        if (ProcessUtil.isNull(sttRequest.getServiceName())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
        }else if (ProcessUtil.isNull(sttRequest.getDescription())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
        } else if (ProcessUtil.isNull(sttRequest.getTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt taskType missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.API) &&
            ProcessUtil.isNull(sttRequest.getApiTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt ApiTaskType with Api type missing.");
        } else if (sttRequest.getTaskType().equals(TaskType.KAFKA) &&
            ProcessUtil.isNull(sttRequest.getKafkaTaskType())) {
            return new AppResponse(ProcessUtil.ERROR, "Stt KafkaTaskType with Api type missing.");
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
            KafkaTaskType kafkaTaskType = new KafkaTaskType();
            if (ProcessUtil.isNull(kafkaTaskTypeRequest.getNumPartitions())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt description missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicName())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            } else if (ProcessUtil.isNull(kafkaTaskTypeRequest.getTopicPattern())) {
                return new AppResponse(ProcessUtil.ERROR, "Stt serviceName missing.");
            }
            kafkaTaskType.setNumPartitions(kafkaTaskTypeRequest.getNumPartitions());
            kafkaTaskType.setTopicName(kafkaTaskTypeRequest.getTopicName());
            kafkaTaskType.setTopicPattern(kafkaTaskTypeRequest.getTopicPattern());
            kafkaTaskType.setStatus(Status.Active);
            sourceTaskType = this.sourceTaskTypeRepository.save(sourceTaskType);
            kafkaTaskType.setSourceTaskType(sourceTaskType);
            this.kafkaTaskTypeRepository.save(kafkaTaskType);
        }
        sttRequest.setSttId(sourceTaskType.getSourceTaskTypeId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "STT save with %d.",sttRequest.getSttId()));
    }

    @Override
    public AppResponse editSTT(STTRequest sttRequest) {
        return null;
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

    @Override
    public AppResponse addSTTF(STTFormRequest sttFormRequest) {
        return null;
    }

    @Override
    public AppResponse editSTTF(STTFormRequest sttFormRequest) {
        return null;
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
        return null;
    }

    @Override
    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) {
        return null;
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
