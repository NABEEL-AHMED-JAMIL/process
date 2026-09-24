package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
public interface SettingService {

    public ResponseDto appSetting() throws Exception;

    public ResponseDto topicsForProfile(Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto topics(String q, Integer limit, List<Long> ids, Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto addSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto updateSourceTaskType(SourceTaskTypeDto sourceTaskTypeDto) throws Exception;

    public ResponseDto deleteSourceTaskType(Long sourceTaskTypeId) throws Exception;

    public ResponseDto fetchKafkaRoute(Long sourceTaskTypeId) throws Exception;

    public ResponseDto setKafkaRoute(Long sourceTaskTypeId, Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto deleteKafkaRoute(Long sourceTaskTypeId) throws Exception;

}