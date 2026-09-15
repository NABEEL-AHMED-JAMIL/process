package process.model.service;

import process.model.dto.FileShareRequestDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface FileShareService {

    ResponseDto emailFile(FileShareRequestDto dto) throws Exception;

    /**
     * Emails bytes this platform just produced, rather than an object it stores.
     *
     * The chat export is not in any bucket -- it is a conversion of a reply the reader is looking
     * at -- so emailFile's bucket/key path cannot carry it. This exists so that the recipient
     * rule, the size ceiling and the message template stay in ONE place: a second copy of
     * "20 MiB and a valid address" is a second thing to get wrong, and the one that drifts is
     * always the copy.
     *
     * @param itemName what the mail calls it, e.g. "chat-export.pdf"
     */
    ResponseDto emailGeneratedFile(String recipientEmail, String itemName, String filename,
        String contentType, byte[] bytes, String message) throws Exception;

}
