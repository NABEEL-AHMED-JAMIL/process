package process.model.service;

import process.model.dto.QueryDefinitionDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface QueryDefinitionService {

    public ResponseDto addQuery(QueryDefinitionDto dto) throws Exception;

    public ResponseDto updateQuery(QueryDefinitionDto dto) throws Exception;

    public ResponseDto deleteQuery(Long queryId) throws Exception;

    public ResponseDto fetchAllQueries() throws Exception;

    /** Includes the decrypted queryText -- for opening a saved query to view/edit it. */
    public ResponseDto fetchQueryById(Long queryId) throws Exception;

    /** dto.queryId (if present) re-validates a saved query's own text; otherwise validates
     * dto.queryText as a draft, before it's ever saved. Syntax/read-only only -- doesn't touch
     * any database connection. */
    public ResponseDto validateQuery(QueryDefinitionDto dto) throws Exception;

    /** Same queryId-or-draft resolution as validateQuery, then actually runs a
     * server-bounded-LIMIT copy of the query against the (ownership-checked) connection profile
     * and returns a small row sample -- never the full result set. */
    public ResponseDto previewQuery(QueryDefinitionDto dto) throws Exception;

}
