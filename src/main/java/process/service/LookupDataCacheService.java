package process.service;

import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.payload.response.LookupDataResponse;

/**
 * @author Nabeel Ahmed
 */
public interface LookupDataCacheService {

    public AppResponse addLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse updateLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse fetchSubLookupByParentId(Long parentLookUpId) throws Exception;

    public AppResponse fetchAllLookup() throws Exception;

    public AppResponse deleteLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public LookupDataResponse getParentLookupById(String lookupType);

    public LookupDataResponse getChildLookupById(String parentLookupType, String childLookupType);

}
