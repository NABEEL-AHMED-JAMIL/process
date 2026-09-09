package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobAuditLogs;
import process.model.projection.JobAuditLogProjection;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface JobAuditLogRepository extends JpaRepository<JobAuditLogs, Long> {

    @Query(value = "select job_audit_log_id as jobAuditLogId, job_queue_id as jobQueueId, log_detail as logsDetail, date_created as dateCreated, status as status, external_id as externalId " +
        "from job_audit_logs where job_queue_id = ? order by date_created asc", nativeQuery = true)
    public List<JobAuditLogProjection> findAllByJobQueueIdV1(Long jobQueueId);

    @Transactional
    @Modifying
    @Query(value = "insert into job_audit_logs (job_audit_log_id, external_id, job_queue_id, log_detail, date_created, status) " +
        "values (nextval('job_audit_logs_source_seq'), ?1, ?2, ?3, ?4, 'Active') on conflict (external_id) do nothing", nativeQuery = true)
    public int upsertFromOpenSearch(String externalId, Long jobQueueId, String logDetail, Timestamp dateCreated);

    /**
     * Of the job_queue ids handed in, the ones job_queue still holds.
     *
     * OpenSearch keeps audit lines long after the run they describe has been dropped from
     * job_queue, and job_queue_id here is a not-null foreign key onto it, so the sync cron was
     * offering upsertFromOpenSearch parents that no longer exist and collecting one
     * fk_job_audit_logs_job_queue violation per row -- 262 of 477 hits in a measured run. The
     * caller asks this once per batch and leaves those rows alone instead of letting the
     * database refuse them one at a time.
     *
     * Typed as Number rather than Long on purpose: a bigint read back through a native query
     * arrives as a BigInteger, so a list declared as Long compiles and then throws a
     * ClassCastException at the first element the caller touches.
     */
    @Query(value = "select job_queue_id from job_queue where job_queue_id in :jobQueueIds", nativeQuery = true)
    public List<Number> findExistingJobQueueIds(@Param("jobQueueIds") List<Long> jobQueueIds);

    @Transactional
    @Modifying
    @Query(value = "update job_audit_logs set status = ?2 where job_queue_id in (select job_queue_id from job_queue where job_id = ?1)", nativeQuery = true)
    public int updateStatusByJobId(Long jobId, String statusName);

}