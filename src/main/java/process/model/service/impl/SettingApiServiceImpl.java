package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTaskType;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.service.SettingApiService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SettingApiServiceImpl implements SettingApiService {

    private Logger logger = LoggerFactory.getLogger(SchedulerApiServiceImpl.class);

    private final String LOOKUP_DATA = "lookupDatas";
    private final String SOURCE_TASK_TYPE = "sourceTaskTaypes";

    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private SourceTaskTypeRepository sourceTaskTypeRepository;

    @Override
    public ResponseDto appSetting() throws Exception {
        Map<String, Object> appSettingDetail = new HashMap<>();
        appSettingDetail.put(LOOKUP_DATA, this.lookupDataRepository.findAll());
        appSettingDetail.put(SOURCE_TASK_TYPE, this.sourceTaskTypeRepository.findAll());
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
        sourceTaskType.setSourceTaskTypeId(UUID.randomUUID().toString());
        sourceTaskType.setServiceName(tempSourceTaskType.getServiceName());
        sourceTaskType.setDescription(tempSourceTaskType.getDescription());
        sourceTaskType.setQueueTopicPartition(tempSourceTaskType.getQueueTopicPartition());
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
            sourceTaskType.get().setQueueTopicPartition(tempSourceTaskType.getQueueTopicPartition());
            this.sourceTaskTypeRepository.save(sourceTaskType.get());
            return new ResponseDto(SUCCESS, String.format("SourceTaskType save with %s.",
                tempSourceTaskType.getSourceTaskTypeId()));
        }
        return new ResponseDto(ERROR, String.format("SourceTaskType not found with %s.",
            tempSourceTaskType.getSourceTaskTypeId()));
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
            this.lookupDataRepository.save(lookupData.get());
            return new ResponseDto(SUCCESS, String.format("LookupData update with %d.", tempLookupData.getLookupId()));
        }
        return new ResponseDto(ERROR, String.format("LookupData not found with %d.", tempLookupData.getLookupId()));
    }
}
