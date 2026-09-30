package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
public interface SettingService {

    ResponseDto appSetting() throws Exception;

    ResponseDto topicsForProfile(Long kafkaConnectionProfileId) throws Exception;

    ResponseDto topics(String q, Integer limit, List<Long> ids, Long kafkaConnectionProfileId) throws Exception;

    ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception;

    ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception;

    ResponseDto setKafkaRoute(Long sourceTaskTypeId, Long kafkaConnectionProfileId) throws Exception;

    ResponseDto deleteKafkaRoute(Long sourceTaskTypeId) throws Exception;

}
