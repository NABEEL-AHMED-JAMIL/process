package process.model.service;

import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface KafkaConnectionProfileService {

    public ResponseDto addProfile(KafkaConnectionProfileDto dto) throws Exception;

    public ResponseDto updateProfile(KafkaConnectionProfileDto dto) throws Exception;

    public ResponseDto deleteProfile(Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto fetchAllProfiles() throws Exception;

    public ResponseDto setAsDefault(Long kafkaConnectionProfileId) throws Exception;

    public ResponseDto clearDefault() throws Exception;

    public ResponseDto testConnection(KafkaConnectionProfileDto dto) throws Exception;

    public ResponseDto testTopicConnection(String topicName) throws Exception;

}
