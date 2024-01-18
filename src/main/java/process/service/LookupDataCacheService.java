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

    public AppResponse addLookupData(LookupDataRequest payload) throws Exception;

    public AppResponse updateLookupData(LookupDataRequest payload) throws Exception;

    public AppResponse fetchSubLookupByParentId(LookupDataRequest payload) throws Exception;

    public AppResponse fetchLookupByLookupType(LookupDataRequest payload) throws Exception;

    public AppResponse fetchAllLookup(LookupDataRequest payload) throws Exception;

    public AppResponse deleteLookupData(LookupDataRequest payload) throws Exception;

    public ByteArrayOutputStream downloadLookupTemplateFile() throws Exception;

    public ByteArrayOutputStream downloadLookup(LookupDataRequest payload) throws Exception;

    public AppResponse uploadLookup(FileUploadRequest payload) throws Exception;

    public LookupDataResponse getParentLookupById(String lookupType);

    public LookupDataResponse getChildLookupById(String parentLookupType, String childLookupType);

}
