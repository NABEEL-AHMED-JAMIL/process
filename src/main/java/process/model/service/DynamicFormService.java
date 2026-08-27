package process.model.service;

import process.model.dto.DynamicFormDto;
import process.model.dto.DynamicFormFieldDto;
import process.model.dto.DynamicFormSubmissionDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface DynamicFormService {

    public ResponseDto addForm(DynamicFormDto dynamicFormDto) throws Exception;

    public ResponseDto updateForm(DynamicFormDto dynamicFormDto) throws Exception;

    public ResponseDto deleteForm(Long dynamicFormId) throws Exception;

    public ResponseDto fetchAllForms() throws Exception;

    public ResponseDto fetchFormByFormId(Long dynamicFormId) throws Exception;

    public ResponseDto fetchFormByUuid(String uuid) throws Exception;

    public ResponseDto addField(Long dynamicFormId, DynamicFormFieldDto dynamicFormFieldDto) throws Exception;

    public ResponseDto updateField(DynamicFormFieldDto dynamicFormFieldDto) throws Exception;

    public ResponseDto deleteField(Long dynamicFormFieldId) throws Exception;

    public ResponseDto submitForm(DynamicFormSubmissionDto dynamicFormSubmissionDto) throws Exception;

    public ResponseDto updateSubmission(DynamicFormSubmissionDto dynamicFormSubmissionDto) throws Exception;

    public ResponseDto deleteSubmission(Long dynamicFormSubmissionId) throws Exception;

    public ResponseDto fetchSubmissionsByFormId(Long dynamicFormId) throws Exception;

    public ResponseDto fetchSubmissionBySubmissionId(Long dynamicFormSubmissionId) throws Exception;

    public ResponseDto fetchSubmissionByUuid(String uuid) throws Exception;

}
