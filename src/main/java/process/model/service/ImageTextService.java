package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ResponseDto;

public interface ImageTextService {

    public ResponseDto extractFromImage(MultipartFile file, Integer x, Integer y, Integer width, Integer height) throws Exception;

}
