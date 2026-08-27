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
 * */
@Repository
public interface QueryScheduleRepository extends JpaRepository<QuerySchedule, Long> {

    List<QuerySchedule> findByStatusNotOrderByScheduleIdDesc(Status status);

    @Query("select s from QuerySchedule s where s.status = process.model.enums.Status.Active and s.nextRunAt <= ?1")
    List<QuerySchedule> findDueSchedules(Timestamp now);

}
