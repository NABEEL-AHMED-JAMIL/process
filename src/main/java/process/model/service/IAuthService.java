package process.model.service;

import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.security.UserPrincipalDetail;

public interface IAuthService {

    public ResponseDto login(LoginRequestDto loginRequest);

    public ResponseDto getCurrentUser(UserPrincipalDetail userPrincipalDetail);

    public ResponseDto signupSuccess(String token, String emailAddress);

}