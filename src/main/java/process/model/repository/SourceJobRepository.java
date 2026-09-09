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

    /**
     * The task and its type are both eager many-to-ones, so without the fetch joins Hibernate
     * follows the root query with a select per distinct task and another per distinct type --
     * a couple of thousand round trips on a tenant with a few hundred tasks. Both are
     * to-one joins, so the row count is unchanged.
     */
    @Query("SELECT sj FROM SourceJob sj LEFT JOIN FETCH sj.sourceTask st "
        + "LEFT JOIN FETCH st.sourceTaskType WHERE sj.jobStatus IN (?1, ?2)")
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
    /**
     * The address a job's notifications belong to: its assigned user's login, which in this
     * system IS an email (app_user has no separate email column, and all 252 accounts are
     * addresses).
     *
     * Returns empty when the job has no assignee, so the caller can decline to send rather than
     * fall back to some other mailbox.
     */
    @Query(value = "select u.username from source_job j "
        + "join app_user u on u.app_user_id = j.assigned_user_id "
        + "where j.job_id = ?1 and u.status <> 'Delete'", nativeQuery = true)
    String findNotificationRecipient(Long jobId);

    @Query(value = "select count(*) from source_job where task_detail_id = ?1 "
        + "and upper(job_status) <> 'DELETE'", nativeQuery = true)
    public long countLiveJobsForTask(Long sourceTaskId);


    /**
     * How many jobs are in one person's name, and how many of those are switched on.
     *
     * Counted in the database rather than by fetching every job in the tenant and filtering in the
     * browser, which is what the profile screen used to do -- the cost of showing somebody their
     * own three jobs grew with the size of the whole workspace.
     */
    // Both columns are aliased, and they have to be: Postgres names an unaliased count(*)
    // "count", so two of them collide and Hibernate's auto-discovery throws
    // NonUniqueDiscoveredSqlAliasException. The profile activity panel returned an error on
    // every single call because of it.
    @Query(value = "select count(*) as total_count, "
        + "count(*) filter (where job_status = 'Active') as active_count "
        + "from source_job where assigned_user_id = ?1 and job_status <> 'Delete'", nativeQuery = true)
    List<Object[]> countAssignedTo(Long appUserId);


    /**
     * How that person's jobs last ran, grouped. Feeds the profile donut, which previously counted
     * the same thing in the browser out of every job in the tenant.
     *
     * A job that has never run has a null running status, and it is grouped under its own label
     * here rather than dropped -- five jobs nobody has run yet is exactly the sort of thing the
     * chart should be showing.
     */
    @Query(value = "select coalesce(job_running_status, 'Not run') as outcome, count(*) "
        + "from source_job where assigned_user_id = ?1 and job_status <> 'Delete' "
        + "group by 1 order by 2 desc", nativeQuery = true)
    List<Object[]> outcomesForAssignee(Long appUserId);

}