package process.model.service;

import process.model.dto.QueryDefinitionDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface QueryDefinitionService {

    public ResponseDto addQuery(QueryDefinitionDto dto) throws Exception;

    public ResponseDto updateQuery(QueryDefinitionDto dto) throws Exception;

    public ResponseDto deleteQuery(Long queryId) throws Exception;

    public ResponseDto fetchAllQueries() throws Exception;

    public ResponseDto fetchQueryById(Long queryId) throws Exception;

    public ResponseDto validateQuery(QueryDefinitionDto dto) throws Exception;

    public ResponseDto previewQuery(QueryDefinitionDto dto) throws Exception;

}
