package process.model.service;

import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.projection.ItemResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SettingService {

    public ResponseDto dynamicQueryResponse(ItemResponse itemResponse);

    public ResponseDto appSetting() throws Exception;

    public ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception;

    /**
     * Fetches the caller's own tenant routing override for a source task type, if one exists
     * -- see TenantTaskTypeKafkaRoute / KafkaConnectionResolver.
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
    public ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception;

    /**
     * Creates or replaces the caller's own tenant routing override for a source task type.
     * @param sourceTaskTypeId
     * @param kafkaConnectionProfileId
     * @return ResponseDto
     * */
    public ResponseDto setKafkaRoute(Long sourceTaskTypeId, Long kafkaConnectionProfileId) throws Exception;

    /**
     * Removes the caller's own tenant routing override for a source task type, falling back to
     * the type's own default profile.
     * @param sourceTaskTypeId
     * @return ResponseDto
     * */
    public ResponseDto deleteKafkaRoute(Long sourceTaskTypeId) throws Exception;

    public ResponseDto addLookupData(LookupDataDto lookupDataDto) throws Exception;

    public ResponseDto updateLookupData(LookupDataDto lookupDataDto) throws Exception;

    public ResponseDto fetchSubLookupByParentId(Long parentLookUpId) throws Exception;

    public ResponseDto deleteLookupData(LookupDataDto lookupDataDto) throws Exception;

}