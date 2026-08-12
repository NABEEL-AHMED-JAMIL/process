package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.QuerySchedule;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface QueryScheduleRepository extends JpaRepository<QuerySchedule, Long> {

    List<QuerySchedule> findByStatusNotOrderByScheduleIdDesc(Status status);

    /** ProcessCron.pollDueQuerySchedules()'s own query -- deliberately unfiltered by tenant
     * (native cron context has no per-request TenantContext to scope against; that's exactly
     * why this is a plain @Query rather than going through the tenantFilter Hibernate filter,
     * same reasoning as SourceJobRepository's own scheduler-facing "due jobs" queries). Every
     * row it returns still gets its tenantId carried through untouched into the resulting
     * QueryExecution, so isolation is preserved -- this method only decides *which* schedules
     * are due, not what data they can see. */
    @Query("select s from QuerySchedule s where s.status = process.model.enums.Status.Active and s.nextRunAt <= ?1")
    List<QuerySchedule> findDueSchedules(Timestamp now);

}
