package process.model.service;

import process.model.dto.FileShareRequestDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface FileShareService {

    ResponseDto emailFile(FileShareRequestDto dto) throws Exception;

}
