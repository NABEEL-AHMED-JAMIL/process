package process.model.service;

import process.model.dto.LoginRequestDto;
import process.model.dto.ResetUserPasswordDto;
import process.model.dto.ResponseDto;
import process.security.UserPrincipalDetail;

public interface IAuthService {

    public ResponseDto login(LoginRequestDto loginRequest);

    public ResponseDto getCurrentUser(UserPrincipalDetail userPrincipalDetail);

    public ResponseDto getUserProfile(UserPrincipalDetail userPrincipalDetail);

    public ResponseDto updateProfile(RealEstateUserDto realEstateUserDto);

    public ResponseDto registerUser(RealEstateUserDto realEstateUserDto);

    public ResponseDto signupSuccess(String token, String emailAddress);

    public ResponseDto forgetUserPassword(String emailAddress);

    public ResponseDto resetPasswordRealEstateUser(ResetUserPasswordDto resetPasswordRealEstateUserDto);

}