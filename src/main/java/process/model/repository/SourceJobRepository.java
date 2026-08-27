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
 * */
@Repository
public interface SourceJobRepository extends JpaRepository<SourceJob, Long> {

    long countByTenantIdAndJobStatusNot(Long tenantId, Status status);

    @Transactional
    @Modifying
    @Query("update SourceJob s set s.tenantId = ?1 where s.tenantId is null")
    int backfillTenantId(Long tenantId);

    @Transactional
    @Modifying
    @Query("update SourceJob s set s.assignedUserId = ?1 where s.assignedUserId is null")
    int backfillAssignedUserId(Long appUserId);

    public Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status);

    public List<SourceJob> findByTenantId(Long tenantId);

    @Query("SELECT sj FROM SourceJob sj WHERE sj.jobStatus IN (?1, ?2)")
    public List<SourceJob> findAllActiveAndInactiveJobs(Status activeStatus, Status inactiveStatus, Sort sort);

    @Query(value = "select sj.job_id as jobId, sj.job_status as jobStatus, sj.job_running_status as jobRunningStatus," +
        "sj.last_job_run as lastJobRun, sc.next_run_at as nextRunAt, sj.execution as execution," +
        "au.username as assignedUsername, sj.assigned_user_id as assignedUserId, sj.tenant_id as tenantId, sj.job_name as jobName\n" +
        "from source_job sj left join scheduler sc on sc.job_id = sj.job_id\n" +
        "left join app_user au on au.app_user_id = sj.assigned_user_id\n" +
        "where sj.job_id in (?1) and UPPER(sj.job_status) = 'ACTIVE'", nativeQuery = true)
    public List<SourceJobProjection> fetchRunningJobEvent(List<Long> jobIds);

    @Transactional
    @Modifying
    @Query(value = "update source_job set job_status = UPPER(?2)\n" +
        "where task_detail_id in (select task_detail_id from source_task where source_task_type_id = ?1)",
        nativeQuery = true)
    public int statusChangeSourceJobLinkWithSourceTaskTypeId(Long sourceTaskTypeId, String status);

    @Transactional
    @Modifying
    @Query(value = "update source_job set job_status = ?2 where task_detail_id = ?1", nativeQuery = true)
    public int statusChangeSourceJobWithSourceTaskId(Long sourceTaskId, String status);

    /**
     * Jobs still bound to a task and not themselves deleted.
     *
     * Deleting a task takes every job with it, so this is what stands between removing one
     * unused task and quietly removing a few hundred jobs along with it.
     */
    @Query(value = "select count(*) from source_job where task_detail_id = ?1 "
        + "and upper(job_status) <> 'DELETE'", nativeQuery = true)
    public long countLiveJobsForTask(Long sourceTaskId);

}