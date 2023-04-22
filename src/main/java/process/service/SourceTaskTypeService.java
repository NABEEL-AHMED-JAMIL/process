package process.service;

import process.payload.request.*;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    // done
    public AppResponse addSTT(STTRequest sttRequest) throws Exception;

    // done
    public AppResponse editSTT(STTRequest sttRequest) throws Exception;

    // done
    public AppResponse deleteSTT(STTRequest sttRequest) throws Exception;

    // done
    public AppResponse fetchSTTBySttId(STTRequest sttRequest) throws Exception;

    // done
    public AppResponse fetchSTT(STTRequest sttRequest) throws Exception;

    public AppResponse linkSTTWithAppUser(STTRequest sttRequest) throws Exception;

    // done
    public AppResponse addSTTF(STTFormRequest sttFormRequest) throws Exception;

    // done
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

    public AppResponse linkSTTSWithFrom() throws Exception;

    public AppResponse addSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse editSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse deleteSTTC(STTControlRequest sttControlRequest) throws Exception;

    public AppResponse fetchSTTC() throws Exception;

    public AppResponse downloadSTTCTree();

    public AppResponse linkSTTCWithFrom();

    public AppResponse downloadSTTCommonTemplateFile();

    public AppResponse downloadSTTCommon();

    public AppResponse uploadSTTCommon(FileUploadRequest fileObject);

}
