package process.model.service;

import process.model.dto.AppUserDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface AppUserService {

    public ResponseDto listUsers() throws Exception;

    /** A user's picture, or null when there is none or the caller may not see that person. */
    public ObjectContentDto readAvatar(Long appUserId);

    public ResponseDto addUser(AppUserDto appUserDto) throws Exception;

    public ResponseDto updateUser(AppUserDto appUserDto) throws Exception;

    public ResponseDto changeUserStatus(AppUserDto appUserDto) throws Exception;

    public ResponseDto resetPassword(AppUserDto appUserDto) throws Exception;

    public ResponseDto currentUser() throws Exception;

    public ResponseDto updateOwnProfile(AppUserDto appUserDto) throws Exception;

    public ResponseDto changeOwnPassword(String currentPassword, String newPassword) throws Exception;

    public ResponseDto updateOwnAvatar(AppUserDto appUserDto) throws Exception;

}
