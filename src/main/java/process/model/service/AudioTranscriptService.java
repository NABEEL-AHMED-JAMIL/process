package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface AudioTranscriptService {

    public ResponseDto extractFromUpload(MultipartFile file, boolean timestamps) throws Exception;

    public ResponseDto extractFromBucket(AudioExtractBucketRequestDto request) throws Exception;

}
