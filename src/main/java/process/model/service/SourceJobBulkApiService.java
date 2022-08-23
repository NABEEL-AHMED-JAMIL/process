package process.model.service;

import process.model.dto.*;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SourceJobBulkApiService {

    public ByteArrayOutputStream downloadSourceJobTemplateFile() throws Exception;

    public ByteArrayOutputStream downloadListSourceJob() throws Exception;

    public ResponseDto uploadSourceJob(FileUploadDto object) throws Exception;

}