package process.model.service;

import process.model.dto.FileChatExportRequestDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface FileChatService {

    ResponseDto prepareContext(String bucket, String key, Long aiAgentId) throws Exception;

    ResponseDto sendMessage(FileChatMessageRequestDto dto) throws Exception;

    ResponseDto exportFile(FileChatExportRequestDto dto) throws Exception;

    /**
     * The same conversion as {@link #exportFile}, delivered by email instead of to the browser.
     *
     * Separate from exportFile rather than a flag on it because the two differ in what they are:
     * one hands bytes back to the caller who asked for them, the other sends them to an address
     * -- an outbound path that has to validate a recipient and respect a size ceiling. Sharing a
     * method would mean one of those checks running on a request that does not need it, or not
     * running on one that does.
     */
    ResponseDto emailExport(FileChatExportRequestDto dto) throws Exception;

    public ResponseDto endSession(String bucket, String key) throws Exception;
}
