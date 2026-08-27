package process.model.service;

/**
 * @author Nabeel Ahmed
 * */
public interface FileChatExtractionService {

    String extractText(String bucket, String key, String etag) throws Exception;

    byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception;


    public void forgetExtraction(String bucket, String key, String etag);
}
