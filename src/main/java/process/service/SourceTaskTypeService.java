package process.service;

import process.model.pojo.STTControl;
import process.payload.request.*;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskTypeService {

    public AppResponse addSTT(STTRequest sttRequest) throws Exception;

    public AppResponse editSTT(STTRequest sttRequest) throws Exception;

    public AppResponse deleteSTT(STTRequest sttRequest) throws Exception;

    public AppResponse viewSTT() throws Exception;

    public AppResponse fetchSTT() throws Exception;

    public AppResponse downloadSTTTree() throws Exception;

    public AppResponse linkSTTWithFrom() throws Exception;

    // STTF
    public AppResponse addSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse editSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse deleteSTTF(STTFormRequest sttFormRequest) throws Exception;

    public AppResponse viewSTTF() throws Exception;

    public AppResponse fetchSTTF() throws Exception;

    public AppResponse downloadSTTFTree() throws Exception;

    public AppResponse linkSTTFWithFrom() throws Exception;

    // STTS
    public AppResponse addSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse editSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse deleteSTTS(STTSectionRequest sttSectionRequest) throws Exception;

    public AppResponse viewSTTS() throws Exception;

    public AppResponse fetchSTTS() throws Exception;

    public AppResponse downloadSTTSTree() throws Exception;

    public AppResponse linkSTTSWithFrom() throws Exception;

    // STTC
    public AppResponse addSTTC(STTControl sttControl) throws Exception;

    public AppResponse editSTTC(STTControl sttControl) throws Exception;

    public AppResponse deleteSTTC(STTControl sttControl) throws Exception;

    public AppResponse fetchSTTC() throws Exception;

    public AppResponse downloadSTTCTree();

    public AppResponse linkSTTCWithFrom();

    // bath
    public AppResponse downloadSTTCommonTemplateFile();

    public AppResponse downloadSTTCommon();

    public AppResponse uploadSTTCommon(FileUploadRequest fileObject);

}
