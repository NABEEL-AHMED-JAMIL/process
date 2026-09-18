package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import process.model.projection.TopicOptionProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, Long> {

    String TOPIC_OPTION_SELECT = "select source_task_type_id as sourceTaskTypeId, service_name as serviceName,\n" +
        "queue_topic_partition as queueTopicPartition, kafka_connection_profile_id as kafkaConnectionProfileId, tenant_id as tenantId,\n" +
        "CONCAT(UPPER(SUBSTR(CAST(task_type_status as varchar), 1, 1)), LOWER(SUBSTR(CAST(task_type_status as varchar), 2))) as status\n" +
        "from source_task_type where task_type_status <> 'Delete'\n";

    /**
     * The topics a picker offers for what was typed: the first {@code limit} whose name or
     * Kafka topic contains the (already lower-cased, %-wrapped) term; blank matches everything.
     * A tenant id of 0 means every workspace -- a platform admin's view.
     */
    @Query(value = TOPIC_OPTION_SELECT +
        "and (:tenantId = 0 or tenant_id = :tenantId)\n" +
        "and (:q = '' or lower(service_name) like :q or lower(coalesce(queue_topic_partition, '')) like :q)\n" +
        "order by service_name asc", nativeQuery = true)
    public List<TopicOptionProjection> searchTopicOptions(@Param("tenantId") long tenantId, @Param("q") String q,
        org.springframework.data.domain.Pageable limit);

    /** The picker rows for known ids -- how a box shows the label of a value it was handed. */
    @Query(value = TOPIC_OPTION_SELECT + "and source_task_type_id in (:ids) order by service_name asc", nativeQuery = true)
    public List<TopicOptionProjection> fetchTopicOptionsByIds(@Param("ids") java.util.Collection<Long> ids);

    /** One Kafka profile's topics as picker rows; on the default profile the workspace's unrouted topics ride along. */
    @Query(value = TOPIC_OPTION_SELECT +
        "and (kafka_connection_profile_id = :profileId\n" +
        "     or (:includeUnrouted = true and kafka_connection_profile_id is null and tenant_id = :tenantId))\n" +
        "order by service_name asc", nativeQuery = true)
    public List<TopicOptionProjection> fetchTopicOptionsForProfile(@Param("profileId") long profileId,
        @Param("includeUnrouted") boolean includeUnrouted, @Param("tenantId") long tenantId);

    public Optional<SourceTaskType> findSourceTaskTypeBySourceTaskTypeIdAndStatus(Long sourceTaskTypeId, Status status);

    long countByTenantIdAndStatusNot(Long tenantId, Status status);

    public List<SourceTaskType> findByStatus(Status status);

    /**
     * Whether a task type that still exists points at this Kafka connection profile.
     *
     * Deliberately not a plain existsByKafkaConnectionProfileId. A task type is never removed from
     * this table -- deleteSourceTaskType only sets task_type_status = Delete and leaves
     * kafka_connection_profile_id sitting on the row -- so the unqualified check counted task types
     * that no screen lists and nobody can reassign, and the profile they once used could never be
     * deleted again.
     */
    public boolean existsByKafkaConnectionProfileIdAndStatusNot(Long kafkaConnectionProfileId, Status status);

    String FETCH_ALL_SOURCE_TASK_TYPE_SELECT = "select source_task_type.source_task_type_id as sourceTaskTypeId, source_task_type.description as description, \n" +
        "CONCAT(UPPER(SUBSTR(CAST(task_type_status as varchar), 1, 1)), LOWER(SUBSTR(CAST(task_type_status as varchar), 2))) as status,\n" +
        "source_task_type.queue_topic_partition as queueTopicPartition, source_task_type.service_name as serviceName,\n" +
        "source_task_type.kafka_connection_profile_id as kafkaConnectionProfileId,\n" +
        "count(source_task.source_task_type_id) as totalTaskLink\n" +
        "from source_task_type\n" +

        "left join source_task on source_task.source_task_type_id = source_task_type.source_task_type_id\n" +
        "and source_task.task_status != 'Delete'\n";

    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskType();

    // One workspace, its own task types, and nothing else.
    //
    // This used to carry "or source_task_type.tenant_id is null", because a NULL owner meant
    // "the platform's, shared with everyone". That reading was load-bearing for exactly one row
    // and an accident for five others: a platform admin creating a task type got a NULL owner
    // (SettingServiceImpl.getSourceTaskType), so five per-workspace types called "Test User 1-5
    // Task" were on every tenant's Task Types screen, carrying their Kafka topic names with them.
    // V39 gave every row an owner and made the column NOT NULL, so there is no longer a value
    // that means "everyone" and no clause here to honour one.
    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "where source_task_type.tenant_id = :tenantId\n" +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskTypeForTenant(@Param("tenantId") Long tenantId);

    /**
     * The topics that publish through one Kafka profile: those that name it, plus -- when the
     * profile is that workspace's default -- the workspace's topics that name no profile at all,
     * since the resolver sends those here. Asked per profile so a workspace with ten thousand
     * topics loads the pane it is looking at, not every pane at once.
     */
    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "where source_task_type.task_type_status <> 'Delete' and ("
        + "source_task_type.kafka_connection_profile_id = :profileId "
        + "or (:includeUnrouted = true and source_task_type.kafka_connection_profile_id is null "
        + "    and source_task_type.tenant_id = :tenantId))\n" +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.service_name asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchTopicsForProfile(@Param("profileId") Long profileId,
        @Param("includeUnrouted") boolean includeUnrouted, @Param("tenantId") Long tenantId);

}
