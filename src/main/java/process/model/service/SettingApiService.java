package process.model.service;

import process.model.payload.request.QueryRequest;
import process.model.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SettingApiService {

    public AppResponse dynamicQueryResponse(QueryRequest payload);

}