package process.model.service;

import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface AppUserService {

    public ResponseDto listUsers() throws Exception;

    public ResponseDto addUser(AppUserDto appUserDto) throws Exception;

    public ResponseDto updateUser(AppUserDto appUserDto) throws Exception;

    public ResponseDto changeUserStatus(AppUserDto appUserDto) throws Exception;

    public ResponseDto resetPassword(AppUserDto appUserDto) throws Exception;

}
