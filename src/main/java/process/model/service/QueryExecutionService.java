package process.model.service;

import process.model.dto.QueryExecutionRequestDto;
import process.model.dto.ResponseDto;
import process.model.pojo.QuerySchedule;

public interface QueryExecutionService {

    public ResponseDto execute(QueryExecutionRequestDto request) throws Exception;

    public void executeForSchedule(QuerySchedule schedule) throws Exception;

    public ResponseDto fetchExecutionById(Long executionId) throws Exception;

    public ResponseDto fetchAllExecutions() throws Exception;

    public ResponseDto fetchExecutionsByQueryId(Long queryId) throws Exception;

}
