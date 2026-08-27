package process.model.service;

import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface AuthService {

    public ResponseDto login(LoginRequestDto loginRequestDto) throws Exception;

    public ResponseDto refresh(String refreshToken) throws Exception;

}
