package process.service;

import process.payload.request.QueryRequest;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SettingApiService {

    public AppResponse dynamicQueryResponse(QueryRequest queryRequest);

}