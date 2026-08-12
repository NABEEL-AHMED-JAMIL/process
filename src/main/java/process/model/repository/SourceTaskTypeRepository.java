package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.SourceTaskType;
import process.model.projection.SourceTaskTypeProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceTaskTypeRepository extends JpaRepository<SourceTaskType, Long> {

    /**
     * Note :- Method use to find the source task type by source task type id and status
     * @param sourceTaskTypeId
     * @param status
     * @return List<SourceTaskType>
     * */
    public Optional<SourceTaskType> findSourceTaskTypeBySourceTaskTypeIdAndStatus(Long sourceTaskTypeId, Status status);

    /**
     * Note :- Method use to find all source task types with a given status -- used to
     * auto-provision Kafka topics for every existing (Active) row on application startup.
     * @param status
     * @return List<SourceTaskType>
     * */
    public List<SourceTaskType> findByStatus(Status status);

    /**
     * Note :- Method use by KafkaConnectionProfileServiceImpl.deleteProfile's "still in use"
     * guard -- an existence check pushed down to the database instead of pulling every
     * SourceTaskType row (across every tenant) into memory just to answer a yes/no question.
     * Deliberately not status-filtered (matches the check this replaced): a profile referenced
     * by even a soft-deleted SourceTaskType still blocks deletion, same as before.
     * @param kafkaConnectionProfileId
     * @return boolean
     * */
    public boolean existsByKafkaConnectionProfileId(Long kafkaConnectionProfileId);

    /** Shared select/join/group for fetchAllSourceTaskType()/fetchAllSourceTaskTypeForTenant()
     * below -- split into two queries (rather than one with a "(:tenantId is null or ...)" OR)
     * so an unscoped (PLATFORM_ADMIN) call never binds a null Long over JDBC at all. pgjdbc has
     * a documented quirk binding an untyped null parameter as `bytea`; Postgres then fails
     * type-checking that placeholder against tenant_id (bigint) with "operator does not exist:
     * bigint = bytea" (or "cannot cast type bytea to bigint" if you try to paper over it with an
     * explicit SQL cast) even though the "is null" branch would've short-circuited it at runtime
     * -- Postgres type-checks every branch of an OR at parse time, not just the one that ends up
     * true. Not binding the null at all sidesteps the whole issue. Kafka schema fields
     * (is_schema_register/schema_payload) were dropped -- see V11 migration, they were
     * display-only and never wired to a real schema-registry integration. */
    String FETCH_ALL_SOURCE_TASK_TYPE_SELECT = "select source_task_type.source_task_type_id as sourceTaskTypeId, source_task_type.description as description, \n" +
        "CONCAT(UPPER(SUBSTR(CAST(task_type_status as varchar), 1, 1)), LOWER(SUBSTR(CAST(task_type_status as varchar), 2))) as status,\n" +
        "source_task_type.queue_topic_partition as queueTopicPartition, source_task_type.service_name as serviceName,\n" +
        "source_task_type.kafka_connection_profile_id as kafkaConnectionProfileId,\n" +
        "count(source_task.source_task_type_id) as totalTaskLink\n" +
        "from source_task_type\n" +
        // Deleted tasks don't count toward "Link Task" -- the count on this screen should match
        // what the Link Source Task picker actually lists. On the ON clause (not WHERE) so a
        // type whose only tasks are all deleted still shows up with a count of 0, rather than
        // disappearing from this list entirely.
        "left join source_task on source_task.source_task_type_id = source_task_type.source_task_type_id\n" +
        "and source_task.task_status != 'Delete'\n";

    /**
     * Note :- Method use to find every source task type, unscoped (PLATFORM_ADMIN) -- every row
     * across every tenant, plus every shared/global (tenant_id null) row.
     * @return List<SourceTaskTypeProjection>
     * */
    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskType();

    /**
     * Note :- Tenant-scoped sibling of the method above -- that tenant's own tenant_id rows plus
     * every shared/global tenant_id-is-null row.
     * @param tenantId
     * @return List<SourceTaskTypeProjection>
     * */
    @Query(value = FETCH_ALL_SOURCE_TASK_TYPE_SELECT +
        "where source_task_type.tenant_id = :tenantId or source_task_type.tenant_id is null\n" +
        "group by source_task_type.source_task_type_id\n" +
        "order by source_task_type.source_task_type_id asc", nativeQuery = true)
    public List<SourceTaskTypeProjection> fetchAllSourceTaskTypeForTenant(@Param("tenantId") Long tenantId);

}
