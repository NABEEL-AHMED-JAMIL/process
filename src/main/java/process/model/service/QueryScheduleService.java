package process.model.service;

import process.model.dto.QueryScheduleDto;
import process.model.dto.ResponseDto;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
public interface QueryScheduleService {

    public ResponseDto addSchedule(QueryScheduleDto dto) throws Exception;

    public ResponseDto updateSchedule(QueryScheduleDto dto) throws Exception;

    public ResponseDto deleteSchedule(Long scheduleId) throws Exception;

    public ResponseDto fetchAllSchedules() throws Exception;

    public ResponseDto fetchScheduleById(Long scheduleId) throws Exception;

    /** ProcessCron.pollDueQuerySchedules()'s own entry point -- unfiltered by tenant (see
     * QueryScheduleRepository.findDueSchedules' own javadoc for why that's safe). */
    public List<process.model.pojo.QuerySchedule> findDueSchedules(Timestamp now);

    /** Advances a schedule's nextRunAt by its own intervalMinutes, called once per firing
     * regardless of whether that run succeeded or failed -- a persistently-failing schedule
     * (bad credentials, query now broken) still needs to stop being "due" every single poll
     * tick, or it would fire again every ~60 seconds forever. Failures are still fully visible
     * in the query_execution history either way. */
    public void advanceNextRun(Long scheduleId) throws Exception;

}
