package process.model.service;

import process.model.dto.FileChatExportRequestDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ResponseDto;

public interface FileChatService {

    ResponseDto prepareContext(String bucket, String key) throws Exception;

    ResponseDto sendMessage(FileChatMessageRequestDto dto) throws Exception;

    ResponseDto exportFile(FileChatExportRequestDto dto) throws Exception;


    public ResponseDto endSession(String bucket, String key) throws Exception;
}
