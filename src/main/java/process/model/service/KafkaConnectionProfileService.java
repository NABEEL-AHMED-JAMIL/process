package process.model.service;

import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface KafkaConnectionProfileService {

    /** Saved under the caller's own tenant (or platform-wide, for PLATFORM_ADMIN -- see impl). */
    public ResponseDto addProfile(KafkaConnectionProfileDto dto) throws Exception;

    public ResponseDto updateProfile(KafkaConnectionProfileDto dto) throws Exception;

    /** Rejected if any SourceTaskType or tenant routing override still references this profile
     * -- same "can't delete what's in use" guard as SourceTaskServiceImpl.deleteSourceTask. */
    public ResponseDto deleteProfile(Long kafkaConnectionProfileId) throws Exception;

    /** The caller's own tenant's profiles plus every platform-wide/shared one; PLATFORM_ADMIN
     * gets every profile across every tenant, unscoped. */
    public ResponseDto fetchAllProfiles() throws Exception;

    /**
     * Marks the given profile as its tenant's (or the platform's, for a null-tenant profile)
     * default -- clearing that flag on every other profile in the same scope -- and invalidates
     * its cached KafkaTemplate so the next resolution rebuilds a producer against it.
     * @param kafkaConnectionProfileId
     * @return ResponseDto
     * */
    public ResponseDto setAsDefault(Long kafkaConnectionProfileId) throws Exception;

    /**
     * Clears the caller's tenant (or the platform's) default profile, falling back the rest of
     * KafkaConnectionResolver's chain (task-type default -> env fallback).
     * @return ResponseDto
     * */
    public ResponseDto clearDefault() throws Exception;

    /**
     * Tests connectivity for a profile without saving it or making it default -- either an
     * already-saved profile (by id) or an in-flight one being created (full config in the
     * Dto), so the UI can validate before Save. Persists the result (connectionStatus/
     * lastTestedAt/lastTestMessage) when testing an already-saved profile.
     * @param dto
     * @return ResponseDto
     * */
    public ResponseDto testConnection(KafkaConnectionProfileDto dto) throws Exception;

    /**
     * Tests that the given topic is reachable on the caller's tenant default cluster (or the
     * platform-wide/env fallback) -- used by Source TaskType's per-row Test action to verify its
     * queueTopicPartition topic.
     * @param topicName
     * @return ResponseDto
     * */
    public ResponseDto testTopicConnection(String topicName) throws Exception;

}
