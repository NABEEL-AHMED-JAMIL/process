package process.model.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.projection.SourceJobProjection;
import java.util.Optional;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceJobRepository extends JpaRepository<SourceJob, Long> {

    /** Phase 0 migration backfill -- see TenantSeedService. */
    @Transactional
    @Modifying
    @Query("update SourceJob s set s.tenantId = ?1 where s.tenantId is null")
    int backfillTenantId(Long tenantId);

    /** Note :- Backfills assignedUserId (added after per-user WebSocket notifications were
     * introduced -- see BulkAction.sendJobStatusNotification) on rows that predate it, or were
     * created through a path that doesn't set it (bulk upload -- see SourceJobBulkServiceImpl).
     * Without an assignee, a job's run status never gets pushed to anyone. */
    @Transactional
    @Modifying
    @Query("update SourceJob s set s.assignedUserId = ?1 where s.assignedUserId is null")
    int backfillAssignedUserId(Long appUserId);

    /**
     * Note :- Method use to get the job by status and the job id
     * @param jobId
     * @return status
     * @return Optional<Job>
     * */
    public Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status);

    /**
     * Note :- Method use to fetch every job owned by one tenant -- scoped at the query level
     * (unlike findAll().stream().filter(...)) so a tenant's Excel export doesn't pull every
     * other tenant's rows into memory just to discard them. See SourceJobBulkServiceImpl.
     * @param tenantId
     * @return List<SourceJob>
     * */
    public List<SourceJob> findByTenantId(Long tenantId);

    /**
     * Note :- Method use to fetch active and inactive jobs with sorting
     * @param activeStatus
     * @param inactiveStatus
     * @param sort
     * @return List<SourceJob>
     * */
    @Query("SELECT sj FROM SourceJob sj WHERE sj.jobStatus IN (?1, ?2)")
    public List<SourceJob> findAllActiveAndInactiveJobs(Status activeStatus, Status inactiveStatus, Sort sort);

    /**
     * Note :- Method use to get the job detail which use to update the view table
     * @param jobIds
     * @return List<SourceJobProjection>
     * */
    @Query(value = "select sj.job_id as jobId, sj.job_status as jobStatus, sj.job_running_status as jobRunningStatus," +
        "sj.last_job_run as lastJobRun, sc.recurrence_time as recurrenceTime, sj.execution as execution," +
        "au.username as assignedUsername\n" +
        "from source_job sj left join scheduler sc on sc.job_id = sj.job_id\n" +
        "left join app_user au on au.app_user_id = sj.assigned_user_id\n" +
        "where sj.job_id in (?1) and UPPER(sj.job_status) = 'ACTIVE'", nativeQuery = true)
    public List<SourceJobProjection> fetchRunningJobEvent(List<Long> jobIds);

    /**
     * Note :- Method use to change the source job link with source task type id
     * @param sourceTaskTypeId
     * @param status Status enum value's name (must be uppercase - e.g., 'DELETE', 'INACTIVE')
     * @return int count of updated records
     * */
    @Transactional
    @Modifying
    @Query(value = "update source_job set job_status = UPPER(?2)\n" +
        "where task_detail_id in (select task_detail_id from source_task where source_task_type_id = ?1)",
        nativeQuery = true)
    public int statusChangeSourceJobLinkWithSourceTaskTypeId(Long sourceTaskTypeId, String status);

    /**
     * Note :- Method use to change status for source job with source task id
     * @param sourceTaskId
     * @param status Status enum value's name (PascalCase - e.g., 'Delete', 'Inactive')
     * @return int count of updated records
     * */
    @Transactional
    @Modifying
    @Query(value = "update source_job set job_status = ?2 where task_detail_id = ?1", nativeQuery = true)
    public int statusChangeSourceJobWithSourceTaskId(Long sourceTaskId, String status);

}