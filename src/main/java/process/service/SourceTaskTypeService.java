package process.service;

import process.model.pojo.STTControl;
import process.payload.request.*;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    public AppResponse addSTT(STTRequest sttRequest);

    public AppResponse editSTT(STTRequest sttRequest);

    public AppResponse deleteSTT(STTRequest sttRequest);

    public AppResponse viewSTT();

    public AppResponse fetchSTT();

    public AppResponse downloadSTTTree();

    public AppResponse linkSTTWithFrom();

    // STTF
    public AppResponse addSTTF(STTFormRequest sttFormRequest);

    public AppResponse editSTTF(STTFormRequest sttFormRequest);

    public AppResponse deleteSTTF(STTFormRequest sttFormRequest);

    public AppResponse viewSTTF();

    public AppResponse fetchSTTF();

    public AppResponse downloadSTTFTree();

    public AppResponse linkSTTFWithFrom();

    // STTS
    public AppResponse addSTTS(STTSectionRequest sttSectionRequest);

    public AppResponse editSTTS(STTSectionRequest sttSectionRequest);

    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest);

    public AppResponse viewSTTS();

    public AppResponse fetchSTTS();

    public AppResponse downloadSTTSTree();

    public AppResponse linkSTTSWithFrom();

    // STTC
    public AppResponse addSTTC(STTControl sttControl);

    public AppResponse editSTTC(STTControl sttControl);

    public AppResponse deleteSTTC(STTControl sttControl);

    public AppResponse fetchSTTC();

    public AppResponse downloadSTTCTree();

    public AppResponse linkSTTCWithFrom();

    // bath
    public AppResponse downloadSTTCommonTemplateFile();

    public AppResponse downloadSTTCommon();

    public AppResponse uploadSTTCommon(FileUploadRequest fileObject);

}
