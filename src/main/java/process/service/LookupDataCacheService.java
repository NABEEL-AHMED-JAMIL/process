package process.service;

import process.payload.request.FileUploadRequest;
import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.payload.response.LookupDataResponse;

import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface LookupDataCacheService {

    public AppResponse addLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse updateLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse fetchSubLookupByParentId(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse fetchLookupByLookupType(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse fetchAllLookup(LookupDataRequest lookupDataRequest) throws Exception;

    public AppResponse deleteLookupData(LookupDataRequest lookupDataRequest) throws Exception;

    public ByteArrayOutputStream downloadLookupTemplateFile() throws Exception;

    public ByteArrayOutputStream downloadLookup(LookupDataRequest tempLookupData) throws Exception;

    public AppResponse uploadLookup(FileUploadRequest fileObject) throws Exception;

    public LookupDataResponse getParentLookupById(String lookupType);

    public LookupDataResponse getChildLookupById(String parentLookupType, String childLookupType);

}
