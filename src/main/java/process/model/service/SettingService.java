package process.model.service;

import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;

/**
 * @author Nabeel Ahmed
 * */
public interface SettingService {

    public ResponseDto appSetting() throws Exception;

    public ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception;

    public ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception;

    public ResponseDto setKafkaRoute(Long sourceTaskTypeId, Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto deleteKafkaRoute(Long sourceTaskTypeId) throws Exception;

    public ResponseDto addLookupData(LookupDataDto lookupDataDto) throws Exception;

    public ResponseDto updateLookupData(LookupDataDto lookupDataDto) throws Exception;

    public ResponseDto fetchSubLookupByParentId(Long parentLookUpId) throws Exception;

    public ResponseDto deleteLookupData(LookupDataDto lookupDataDto) throws Exception;

}