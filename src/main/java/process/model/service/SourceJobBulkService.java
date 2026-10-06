package process.model.service;

import java.io.ByteArrayOutputStream;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface SourceJobBulkService {

    ByteArrayOutputStream downloadSourceJobTemplateFile() throws Exception;

    ByteArrayOutputStream downloadListSourceJob() throws Exception;

    ResponseDto uploadSourceJob(FileUploadDto<?> object) throws Exception;

}
