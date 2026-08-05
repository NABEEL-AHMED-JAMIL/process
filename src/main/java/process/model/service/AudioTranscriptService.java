package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ResponseDto;
import process.model.dto.YoutubeExtractRequestDto;

/**
 * @author Nabeel Ahmed
 */
public interface AudioTranscriptService {

    public ResponseDto extractFromUpload(MultipartFile file, boolean timestamps) throws Exception;

    public ResponseDto extractFromBucket(AudioExtractBucketRequestDto request) throws Exception;

    public ResponseDto extractFromVideoUpload(MultipartFile file, boolean timestamps) throws Exception;

    public ResponseDto extractFromYoutube(YoutubeExtractRequestDto request) throws Exception;

}
