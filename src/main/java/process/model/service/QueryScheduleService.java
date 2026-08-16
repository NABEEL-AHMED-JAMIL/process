package process.model.service;

import process.model.dto.QueryScheduleDto;
import process.model.dto.ResponseDto;
import process.model.pojo.QuerySchedule;
import java.sql.Timestamp;
import java.util.List;

public interface QueryScheduleService {

    public ResponseDto addSchedule(QueryScheduleDto dto) throws Exception;

    public ResponseDto updateSchedule(QueryScheduleDto dto) throws Exception;

    public ResponseDto deleteSchedule(Long scheduleId) throws Exception;

    public ResponseDto fetchAllSchedules() throws Exception;

    public ResponseDto fetchScheduleById(Long scheduleId) throws Exception;

    public List<QuerySchedule> findDueSchedules(Timestamp now);

    public void advanceNextRun(Long scheduleId) throws Exception;

}
