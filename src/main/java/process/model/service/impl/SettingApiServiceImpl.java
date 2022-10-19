package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTaskType;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.service.SettingApiService;
import java.util.*;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SettingApiServiceImpl implements SettingApiService {

    private Logger logger = LoggerFactory.getLogger(SourceJobBulkApiServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String LOOKUP_DATA = "lookupDatas";
    private final String SOURCE_TASK_TYPE = "sourceTaskTaypes";

    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SourceTaskRepository sourceTaskRepository;
    @Autowired
    private SourceTaskTypeRepository sourceTaskTypeRepository;

    @Override
    public ResponseDto appSetting() throws Exception {
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataDto> lookupDataList = new ArrayList<>();
        for (LookupData lookup: this.lookupDataRepository.findByParentLookupIdIsNull()) {
            LookupDataDto lookupDataDto = new LookupDataDto();
            this.fillLookupDateDto(lookup, lookupDataDto);
            if (!isNull(lookup.getParent())) {
                LookupDataDto lookupDataDto2 = new LookupDataDto();
                this.fillLookupDateDto(lookup.getParent(), lookupDataDto2);
                lookupDataDto.setParent(lookupDataDto2);
            }
            lookupDataList.add(lookupDataDto);
        }
        appSettingDetail.put(LOOKUP_DATA, lookupDataList);
        appSettingDetail.put(SOURCE_TASK_TYPE, this.sourceTaskTypeRepository.fetchAllSourceTaskType());
        return new ResponseDto(SUCCESS, "Data fetch successfully.",appSettingDetail);
    }

    @Override
    public ResponseDto addSourceTaskType(SourceTaskTypeDto tempSourceTaskType) throws Exception {
        if (isNull(tempSourceTaskType.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(tempSourceTaskType.getDescription())) {
            return new ResponseDto(ERROR, "SourceTaskType description missing.");
        } else if (isNull(tempSourceTaskType.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setServiceName(tempSourceTaskType.getServiceName());
        sourceTaskType.setDescription(tempSourceTaskType.getDescription());
        sourceTaskType.setQueueTopicPartition(tempSourceTaskType.getQueueTopicPartition());
        sourceTaskType.setSchemaPayload(tempSourceTaskType.getSchemaPayload());
        sourceTaskType.setSchemaRegister(isNull(tempSourceTaskType.getSchemaPayload()) ? false: true);
        sourceTaskType.setStatus(Status.Active);
        this.sourceTaskTypeRepository.save(sourceTaskType);
        return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskType.getSourceTaskTypeId()));
    }

    @Override
    public ResponseDto updateSourceTaskType(SourceTaskTypeDto tempSourceTaskType) throws Exception {
        if (isNull(tempSourceTaskType.getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTaskType sourceTaskTypeId missing.");
        } else if (isNull(tempSourceTaskType.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(tempSourceTaskType.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(tempSourceTaskType.getSourceTaskTypeId());
        if (sourceTaskType.isPresent()) {
            sourceTaskType.get().setServiceName(tempSourceTaskType.getServiceName());
            sourceTaskType.get().setDescription(tempSourceTaskType.getDescription());
            sourceTaskType.get().setSchemaPayload(tempSourceTaskType.getSchemaPayload());
            sourceTaskType.get().setQueueTopicPartition(tempSourceTaskType.getQueueTopicPartition());
            sourceTaskType.get().setSchemaRegister(isNull(tempSourceTaskType.getSchemaPayload()) ? false: true);
            /**
             * Note :- Source Task Type Status Impact on Source Job
             * like :- if active the source job active
             * if delete then source job delete
             * if inactive then source job inactive
             * */
            this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(tempSourceTaskType.getSourceTaskTypeId(), tempSourceTaskType.getStatus().name());
            if (!isNull(tempSourceTaskType.getStatus())) {
                sourceTaskType.get().setStatus(tempSourceTaskType.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", tempSourceTaskType.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", tempSourceTaskType.getSourceTaskTypeId()));
    }

    @Override
    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception {
        if (isNull(sourceTaskTypeId)) {
            return new ResponseDto(ERROR, "SourceTaskType sourceTaskTypeId missing.");
        }
        /**
         * Note :- if the queue delete then all the link-source task delete all the source job stop and job status into delete state
         * */
        this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeId, Status.Delete.name());
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
        if (sourceTaskType.isPresent()) {
            sourceTaskType.get().setStatus(Status.Delete);
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
        }
        return new ResponseDto(SUCCESS, String.format("SourceTaskType delete with %s.", sourceTaskTypeId));
    }

    @Override
    public ResponseDto addLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupValue())) {
            return new ResponseDto(ERROR, "LookupData value missing.");
        } else if (isNull(tempLookupData.getLookupType())) {
            return new ResponseDto(ERROR, "LookupData type missing.");
        }
        LookupData lookupData = new LookupData();
        lookupData.setLookupValue(tempLookupData.getLookupValue());
        lookupData.setLookupType(tempLookupData.getLookupType());
        if (!isNull(tempLookupData.getDescription())) {
            lookupData.setDescription(tempLookupData.getDescription());
        }
        if (!isNull(tempLookupData.getParentLookupId())) {
            Optional<LookupData> parentLookupData = this.lookupDataRepository.findById(tempLookupData.getParentLookupId());
            if (parentLookupData.isPresent()) {
                lookupData.setParent(parentLookupData.get());
            }
        }
        this.lookupDataRepository.save(lookupData);
        return new ResponseDto(SUCCESS, String.format("LookupData save with %d.", lookupData.getLookupId()));
    }

    @Override
    public ResponseDto updateLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupId())) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        } else if (isNull(tempLookupData.getLookupValue())) {
            return new ResponseDto(ERROR, "LookupData value missing.");
        } else if (isNull(tempLookupData.getLookupType())) {
            return new ResponseDto(ERROR, "LookupData type missing.");
        }
        Optional<LookupData> lookupData = this.lookupDataRepository.findById(tempLookupData.getLookupId());
        if (lookupData.isPresent()) {
            lookupData.get().setLookupValue(tempLookupData.getLookupValue());
            lookupData.get().setLookupType(tempLookupData.getLookupType());
            if (!isNull(tempLookupData.getDescription())) {
                lookupData.get().setDescription(tempLookupData.getDescription());
            }
            if (!isNull(tempLookupData.getParentLookupId())) {
                Optional<LookupData> parentLookupData = this.lookupDataRepository.findById(tempLookupData.getParentLookupId());
                if (parentLookupData.isPresent()) {
                    lookupData.get().setParent(parentLookupData.get());
                }
            }
            this.lookupDataRepository.save(lookupData.get());
            return new ResponseDto(SUCCESS, String.format("LookupData update with %d.", tempLookupData.getLookupId()));
        }
        return new ResponseDto(ERROR, String.format("LookupData not found with %d.", tempLookupData.getLookupId()));
    }

    @Override
    public ResponseDto fetchSubLookupByParentId(Long parentLookUpId) throws Exception {
        if (isNull(parentLookUpId)) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        }
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataDto> lookupDataList = new ArrayList<>();
        Optional<LookupData> parentLookup = this.lookupDataRepository.findById(parentLookUpId);
        if (parentLookup.isPresent()) {
            LookupDataDto lookupDataDto = new LookupDataDto();
            this.fillLookupDateDto(parentLookup.get(), lookupDataDto);
            appSettingDetail.put(PARENT_LOOKUP_DATA, lookupDataDto);
            if (!isNull(parentLookup.get().getChildren())) {
                for (LookupData lookup: parentLookup.get().getChildren()) {
                    LookupDataDto lookupDataDto2 = new LookupDataDto();
                    this.fillLookupDateDto(lookup, lookupDataDto2);
                    lookupDataList.add(lookupDataDto2);
                }
            }
            appSettingDetail.put(LOOKUP_DATA, lookupDataList);
            return new ResponseDto(SUCCESS, "Data fetch successfully.", appSettingDetail);
        }
        return new ResponseDto(ERROR, String.format("LookupData not found with %d.", parentLookUpId));
    }

    @Override
    public ResponseDto deleteLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupId())) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        }
        this.lookupDataRepository.deleteById(tempLookupData.getLookupId());
        return new ResponseDto(SUCCESS, String.format("LookupData delete with %d.", tempLookupData.getLookupId()));
    }

    private void fillLookupDateDto(LookupData lookupData, LookupDataDto lookupDataDto) {
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setLookupValue(lookupData.getLookupValue());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
    }
}
