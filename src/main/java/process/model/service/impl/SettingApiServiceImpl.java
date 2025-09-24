package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTaskType;
import process.model.projection.ItemResponse;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.service.SettingApiService;
import java.util.*;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SettingApiServiceImpl implements SettingApiService {

    private Logger logger = LoggerFactory.getLogger(SettingApiServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String LOOKUP_DATA = "lookupDatas";
    private final String SOURCE_TASK_TYPE = "sourceTaskTypes";

    private final LookupDataRepository lookupDataRepository;
    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final LookupDataCacheService lookupDataCacheService;
    private final QueryService queryService;

    public SettingApiServiceImpl(LookupDataRepository lookupDataRepository,
        SourceJobRepository sourceJobRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        LookupDataCacheService lookupDataCacheService,
        QueryService queryService) {
        this.lookupDataRepository = lookupDataRepository;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.lookupDataCacheService = lookupDataCacheService;
        this.queryService = queryService;
    }

    /**
     * Method use to fetch the dynamic query response
     * @param itemResponse
     * @return ResponseDto
     * */
    @Override
    public ResponseDto dynamicQueryResponse(ItemResponse itemResponse) {
        if (isNull(itemResponse.getQuery())) {
            return new ResponseDto(ERROR, "Query missing.");
        }
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.queryService.executeQueryResponse(itemResponse.getQuery()));
    }

    /**
     * Method use to fetch the lookup data and source task type
     * @return ResponseDto
     * */
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

    /**
     * Method use to add the source task type
     * @param sourceTaskTypeDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception {
        if (isNull(sourceTaskTypeDto.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(sourceTaskTypeDto.getDescription())) {
            return new ResponseDto(ERROR, "SourceTaskType description missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        SourceTaskType sourceTaskType = this.getSourceTaskType(sourceTaskTypeDto);
        this.sourceTaskTypeRepository.save(sourceTaskType);
        return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskType.getSourceTaskTypeId()));
    }

    /**
     * Method use to update the source task type
     * @param sourceTaskTypeDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception {
        if (isNull(sourceTaskTypeDto.getSourceTaskTypeId())) {
            return new ResponseDto(ERROR, "SourceTaskType sourceTaskTypeId missing.");
        } else if (isNull(sourceTaskTypeDto.getServiceName())) {
            return new ResponseDto(ERROR, "SourceTaskType serviceName missing.");
        } else if (isNull(sourceTaskTypeDto.getQueueTopicPartition())) {
            return new ResponseDto(ERROR, "SourceTaskType queueTopicPartition missing.");
        }
        Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeDto.getSourceTaskTypeId());
        if (sourceTaskType.isPresent()) {
            sourceTaskType.get().setServiceName(sourceTaskTypeDto.getServiceName());
            sourceTaskType.get().setDescription(sourceTaskTypeDto.getDescription());
            sourceTaskType.get().setSchemaPayload(sourceTaskTypeDto.getSchemaPayload());
            sourceTaskType.get().setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
            sourceTaskType.get().setSchemaRegister(!isNull(sourceTaskTypeDto.getSchemaPayload()));
            /**
             * Note :- Source Task Type Status Impact on Source Job
             * like :- if active the source job active
             * if delete then source job delete
             * if inactive then source job inactive
             * */
            this.sourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId(sourceTaskTypeDto.getSourceTaskTypeId(), sourceTaskTypeDto.getStatus().name());
            if (!isNull(sourceTaskTypeDto.getStatus())) {
                sourceTaskType.get().setStatus(sourceTaskTypeDto.getStatus());
            }
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.", sourceTaskTypeDto.getSourceTaskTypeId()));
    }

    /**
     * Method use to delete source task type
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
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

    /**
     * Method use to add the new lookup data
     * @param tempLookupData
     * @return ResponseDto
     * */
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
            parentLookupData.ifPresent(lookupData::setParent);
        }
        this.lookupDataRepository.save(lookupData);
        return new ResponseDto(SUCCESS, String.format("LookupData save with %d.", lookupData.getLookupId()));
    }

    /**
     * Method use to update the lookup date
     * @param tempLookupData
     * @return ResponseDto
     * */
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
                parentLookupData.ifPresent(data -> lookupData.get().setParent(data));
            }
            this.lookupDataRepository.save(lookupData.get());
            return new ResponseDto(SUCCESS, String.format("LookupData update with %d.", tempLookupData.getLookupId()));
        }
        return new ResponseDto(ERROR, String.format("LookupData not found with %d.", tempLookupData.getLookupId()));
    }

    /**
     * Method use to fetch teh sub lookup by parent id
     * @param parentLookUpId
     * @return ResponseDto
     * */
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

    /**
     * Method use to fetch all lookup
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchAllLookup() throws Exception {
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.lookupDataCacheService.getLookupCacheMap());
    }

    /**
     * Method use to delete the lookup data
     * @param tempLookupData
     * @return ResponseDto
     * */
    @Override
    public ResponseDto deleteLookupData(LookupDataDto tempLookupData) throws Exception {
        if (isNull(tempLookupData.getLookupId())) {
            return new ResponseDto(ERROR, "LookupData id missing.");
        }
        this.lookupDataRepository.deleteById(tempLookupData.getLookupId());
        return new ResponseDto(SUCCESS, String.format("LookupData delete with %d.", tempLookupData.getLookupId()));
    }

    /**
     * Method use to fill the lookup data dto
     * @param lookupData
     * @param lookupDataDto
     * */
    private void fillLookupDateDto(LookupData lookupData, LookupDataDto lookupDataDto) {
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setLookupValue(lookupData.getLookupValue());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
    }

    /**
     * Method use to get teh source task type
     * @param sourceTaskTypeDto
     * @return SourceTaskType
     * */
    private SourceTaskType getSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setServiceName(sourceTaskTypeDto.getServiceName());
        sourceTaskType.setDescription(sourceTaskTypeDto.getDescription());
        sourceTaskType.setQueueTopicPartition(sourceTaskTypeDto.getQueueTopicPartition());
        sourceTaskType.setSchemaPayload(sourceTaskTypeDto.getSchemaPayload());
        sourceTaskType.setSchemaRegister(!isNull(sourceTaskTypeDto.getSchemaPayload()));
        sourceTaskType.setStatus(Status.Active);
        return sourceTaskType;
    }

}
