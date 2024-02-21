package process.service;

import process.model.pojo.LookupData;
import process.payload.request.*;
import process.payload.response.AppResponse;
import process.util.lookuputil.GLookup;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    public AppResponse addSTT(STTRequest payload) throws Exception;

    public AppResponse editSTT(STTRequest payload) throws Exception;

    public AppResponse deleteSTT(STTRequest payload) throws Exception;

    public AppResponse fetchSTTBySttId(STTRequest payload) throws Exception;

    public AppResponse fetchSTT(STTRequest payload) throws Exception;

    public AppResponse addSTTLinkUser(STTLinkUserRequest payload) throws Exception;

    public AppResponse deleteSTTLinkUser(STTLinkUserRequest payload) throws Exception;

    public AppResponse fetchSTTLinkUser(STTLinkUserRequest payload) throws Exception;

    public AppResponse addSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse deleteSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse fetchSTTLinkSTTF(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse addSTTF(STTFormRequest payload) throws Exception;

    public AppResponse editSTTF(STTFormRequest payload) throws Exception;

    public AppResponse deleteSTTF(STTFormRequest payload) throws Exception;

    public AppResponse fetchSTTFBySttfId(STTFormRequest payload) throws Exception;

    public AppResponse fetchSTTF(STTFormRequest payload) throws Exception;

    public AppResponse addSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse deleteSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse fetchSTTFLinkSTT(STTFLinkSTTRequest payload) throws Exception;

    public AppResponse addSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse deleteSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse fetchSTTFLinkSTTS(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse addSTTS(STTSectionRequest payload) throws Exception;

    public AppResponse editSTTS(STTSectionRequest payload) throws Exception;

    public AppResponse deleteSTTS(STTSectionRequest payload) throws Exception;

    public AppResponse fetchSTTSBySttsId(STTSectionRequest payload) throws Exception;

    public AppResponse fetchSTTS(STTSectionRequest payload) throws Exception;

    public AppResponse addSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse deleteSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse fetchSTTSLinkSTTF(STTSLinkSTTFRequest payload) throws Exception;

    public AppResponse addSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse deleteSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse fetchSTTSLinkSTTC(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse addSTTC(STTControlRequest payload) throws Exception;

    public AppResponse editSTTC(STTControlRequest payload) throws Exception;

    public AppResponse deleteSTTC(STTControlRequest payload) throws Exception;

    public AppResponse fetchSTTCBySttcId(STTControlRequest payload) throws Exception;

    public AppResponse fetchSTTC(STTControlRequest payload) throws Exception;

    public AppResponse addSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse deleteSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse fetchSTTCLinkSTTS(STTCLinkSTTSRequest payload) throws Exception;

    public AppResponse fetchSTTFormDetail(Long formId) throws Exception;

    public AppResponse addSTTCInteractions(STTCInteractionsRequest payload) throws Exception;

    public AppResponse deleteSTTCInteractions(STTCInteractionsRequest payload) throws Exception;

    public ByteArrayOutputStream downloadSTTCommonTemplateFile(STTFileUploadRequest payload) throws Exception;

    public ByteArrayOutputStream downloadSTTCommon(STTFileUploadRequest payload) throws Exception;

    public AppResponse uploadSTTCommon(FileUploadRequest payload) throws Exception;

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
