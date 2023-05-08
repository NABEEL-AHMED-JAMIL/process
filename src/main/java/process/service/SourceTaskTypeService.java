package process.service;

import process.payload.request.*;
import process.payload.response.AppResponse;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    public AppResponse addSTT(STTRequest sttRequest) throws Exception;

    public AppResponse editSTT(STTRequest sttRequest) throws Exception;

    public AppResponse deleteSTT(STTRequest sttRequest) throws Exception;

    public AppResponse fetchSTTBySttId(STTRequest sttRequest) throws Exception;

    public AppResponse fetchSTT(STTRequest sttRequest) throws Exception;

    public AppResponse linkSTTWithAppUser(STTRequest sttRequest) throws Exception;

    public AppResponse addSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse editSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse deleteSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse fetchSTTFBySttfId(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse fetchSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse linkSTTFWithFrom(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse addSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse fetchSTTSBySttsId(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse fetchSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse linkSTTSWithFrom(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse addSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse editSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse deleteSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse fetchSTTCBySttcId(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse fetchSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse linkSTTCWithFrom(STTControlRequest sttControlRequest) throws Exception;

    public ByteArrayOutputStream downloadSTTCommonTemplateFile(STTFileUploadRequest sttFileUReq) throws Exception;

    public ByteArrayOutputStream downloadSTTCommon(STTFileUploadRequest sttFileUReq) throws Exception;

    public AppResponse uploadSTTCommon(FileUploadRequest fileObject) throws Exception;

}
