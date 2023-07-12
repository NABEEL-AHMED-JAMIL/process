package process.service;

import process.model.pojo.LookupData;
import process.payload.request.*;
import process.payload.response.AppResponse;
import process.util.ProcessUtil;
import process.util.lookuputil.GLookup;
import process.util.lookuputil.LookupDetailUtil;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    public AppResponse addSTT(STTRequest sttRequest) throws Exception;

    public AppResponse editSTT(STTRequest sttRequest) throws Exception;

    public AppResponse deleteSTT(STTRequest sttRequest) throws Exception;

    public AppResponse fetchSTTBySttId(STTRequest sttRequest) throws Exception;

    public AppResponse fetchSTT(STTRequest sttRequest) throws Exception;

    public AppResponse addSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception;

    public AppResponse deleteSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception;

    public AppResponse fetchSTTLinkUser(STTLinkUserRequest sttLinkUserRequest) throws Exception;

    public AppResponse addSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse editSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse deleteSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse fetchSTTFBySttfId(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse fetchSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse addSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception;

    public AppResponse deleteSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception;

    public AppResponse fetchSTTFLinkSTT(STTFLinkSTTRequest sttfLinkSTTRequest) throws Exception;

    public AppResponse addSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse fetchSTTSBySttsId(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse fetchSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse addSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception;

    public AppResponse deleteSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception;

    public AppResponse fetchSTTSLinkSTTF(STTSLinkSTTFRequest sttsLinkSTTFRequest) throws Exception;

    public AppResponse addSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse editSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse deleteSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse fetchSTTCBySttcId(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse fetchSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse addSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception;

    public AppResponse deleteSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception;

    public AppResponse fetchSTTCLinkSTTS(STTCLinkSTTSRequest sttcLinkSTTSRequest) throws Exception;

    public ByteArrayOutputStream downloadSTTCommonTemplateFile(STTFileUploadRequest sttFileUReq) throws Exception;

    public ByteArrayOutputStream downloadSTTCommon(STTFileUploadRequest sttFileUReq) throws Exception;

    public AppResponse uploadSTTCommon(FileUploadRequest fileObject) throws Exception;

    public default GLookup getDBLoopUp(Long lookupValue, Optional<LookupData> lookupData) {
        if (lookupData.isPresent()) {
            lookupData = lookupData.get().getLookupChildren()
                .stream().filter(lookupData1 -> lookupData1.getLookupValue()
                .equals(String.valueOf(lookupValue)))
                .findFirst();
            return GLookup.getGLookupByValue(lookupData.get().getLookupType(),
                lookupData.get().getLookupValue(), lookupData.get().getDescription());
        }
        return null;
    }

    public default GLookup getDBLoopUp(Optional<LookupData> lookupData) {
        if (lookupData.isPresent()) {
            return GLookup.getGLookupByValue(lookupData.get().getLookupType(),
                lookupData.get().getLookupValue(), lookupData.get().getDescription());
        }
        return null;
    }

}
