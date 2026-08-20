package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.DocumentConverterTaskDto;
import process.model.dto.ResponseDto;

public interface DocumentConverterService {

    ResponseDto supportedFormats() throws Exception;

    ResponseDto fetchAllTasks() throws Exception;

    ResponseDto fetchTaskById(Long documentConverterTaskId) throws Exception;

    ResponseDto convert(MultipartFile file, String outputFormat, String bucketName, String targetFolder, String taskName, boolean save) throws Exception;

    ResponseDto deleteTask(Long documentConverterTaskId) throws Exception;

}
