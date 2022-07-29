package process.model.service;

import process.model.dto.*;
import java.io.ByteArrayInputStream;

/**
 * @author Nabeel Ahmed
 */
public interface BulkApiService {

    public ByteArrayInputStream downloadBatchSchedulerTemplateFile() throws Exception;

    public ResponseDto uploadJobFile(FileUploadDto object) throws Exception;

}