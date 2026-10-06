package process.model.service;

import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface KafkaConnectionProfileService {

    ResponseDto addProfile(KafkaConnectionProfileDto dto) throws Exception;

    ResponseDto updateProfile(KafkaConnectionProfileDto dto) throws Exception;

    ResponseDto deleteProfile(Long kafkaConnectionProfileId) throws Exception;

    ResponseDto fetchAllProfiles() throws Exception;

    ResponseDto setAsDefault(Long kafkaConnectionProfileId) throws Exception;

    ResponseDto clearDefault() throws Exception;

    ResponseDto testConnection(KafkaConnectionProfileDto dto) throws Exception;

    ResponseDto testTopicConnection(String topicName) throws Exception;

    /** The same check against one named profile, for a topic listed under that profile's pane. */
    ResponseDto testTopicConnection(String topicName, Long kafkaConnectionProfileId) throws Exception;

}
