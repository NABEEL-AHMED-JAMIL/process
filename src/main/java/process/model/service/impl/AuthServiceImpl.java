package process.model.service.impl;

import org.springframework.stereotype.Service;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.IAuthService;
import process.security.UserPrincipalDetail;

/**
 * @author Nabeel Ahmed
 */
@Service
public class AuthServiceImpl implements IAuthService {

    @Override
    public ResponseDto login(LoginRequestDto loginRequest) {
        return null;
    }

    @Override
    public ResponseDto getCurrentUser(UserPrincipalDetail userPrincipalDetail) {
        return null;
    }

    @Override
    public ResponseDto signupSuccess(String token, String emailAddress) {
        return null;
    }
}
