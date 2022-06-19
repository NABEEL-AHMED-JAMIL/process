package process.model.service;

import process.model.dto.*;
import java.io.ByteArrayInputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SchedulerApiService {

    public ByteArrayInputStream downloadBatchSchedulerTemplateFile() throws Exception;

    public ResponseDto uploadJobFile(FileUploadDto object) throws Exception;

}