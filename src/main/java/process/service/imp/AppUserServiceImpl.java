package process.service.imp;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import process.emailer.EmailMessagesFactory;
import process.model.enums.ERole;
import process.model.pojo.*;
import process.model.repository.AppUserEnvRepository;
import process.model.repository.AppUserRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.RoleRepository;
import process.payload.request.*;
import process.payload.response.*;
import process.security.jwt.JwtUtils;
import process.security.service.RefreshTokenService;
import process.security.service.UserDetailsImpl;
import process.service.AppUserService;
import process.service.LookupDataCacheService;
import process.socket.service.NotificationService;
import process.util.excel.BulkExcel;
import process.util.lookuputil.LookupDetailUtil;
import process.util.ProcessUtil;
import process.util.lookuputil.APPLICATION_STATUS;
import process.util.lookuputil.NOTIFICATION_STATUS;
import process.util.lookuputil.NOTIFICATION_TYPE;
import process.util.validation.AppUserValidation;
import javax.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class AppUserServiceImpl implements AppUserService {

    private Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

    @Value("${storage.efsFileDire}")
    private String tempStoreDirectory;
    @Autowired
    private BulkExcel bulkExcel;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private AppUserEnvRepository appUserEnvRepository;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;

    /**
     * Method use to get appUser detail
     * @param username
     * @return AppResponse
     * */
    @Override
    public AppResponse getAppUserProfile(String username) throws Exception {
        logger.info("Request getAppUserProfile :- " + username);
        if (ProcessUtil.isNull(username)) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            username, APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        AppUser appUserDetail = appUser.get();
        AppUserResponse appUserResponse = this.getAppUserDetail(appUserDetail);
        if (!appUserDetail.getAppUserRoles().isEmpty()) {
            appUserResponse.setRoleResponse(appUserDetail.getAppUserRoles()
                .stream().map(role -> {
                    return this.getRoleResponse(role);
                }).collect(Collectors.toSet()));
        }
        if (!ProcessUtil.isNull(appUserDetail.getParentAppUser())) {
            appUserResponse.setParentAppUser(this.getAppUserDetail(appUserDetail.getParentAppUser()));
        }
        return new AppResponse(ProcessUtil.SUCCESS, "User detail.", appUserResponse);
    }


    /**
     * Method use to update the user detail
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserProfile(UpdateUserProfileRequest payload) throws Exception {
        logger.info("Request updateAppUserProfile :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getFirstName())) {
            return new AppResponse(ProcessUtil.ERROR, "FirstName missing.");
        } else if (ProcessUtil.isNull(payload.getLastName())) {
            return new AppResponse(ProcessUtil.ERROR, "LastName missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setFirstName(payload.getFirstName());
        appUser.get().setLastName(payload.getLastName());
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserProfile(payload)) {
            this.notificationService.addNotification(this.getNotificationRequest(
            "Dear user, your application profile updated."), appUser.get());
            return new AppResponse(ProcessUtil.SUCCESS, "AppUser Profile Update.", payload);
        }
        return new AppResponse(ProcessUtil.ERROR, "Account updated, Email not send contact with support.", payload);
    }

    /**
     * Method use to update the app user password
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserPassword(UpdateUserProfileRequest payload) throws Exception {
        logger.info("Request updateAppUserPassword :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getOldPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "OldPassword missing.");
        } else if (ProcessUtil.isNull(payload.getNewPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "NewPassword missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        if (!this.passwordEncoder.matches(payload.getOldPassword(), appUser.get().getPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "Old password not match.");
        }
        appUser.get().setPassword(this.passwordEncoder.encode(payload.getNewPassword()));
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserPassword(payload)) {
            this.notificationService.addNotification(this.getNotificationRequest(
                "Dear user, your password updated."), appUser.get());
            return new AppResponse(ProcessUtil.SUCCESS, "AppUser Profile Update.", payload);
        }
        return new AppResponse(ProcessUtil.ERROR, "Account updated, Email not send contact with support.", payload);
    }

    /**
     * Method use to update the app user timezone
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserTimeZone(UpdateUserProfileRequest payload) throws Exception {
        logger.info("Request updateAppUserTimeZone :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getTimeZone())) {
            return new AppResponse(ProcessUtil.ERROR, "TimeZone missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setTimeZone(payload.getTimeZone());
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserTimeZone(payload)) {
            this.notificationService.addNotification(this.getNotificationRequest(
            "Dear user, your timezone updated."), appUser.get());
            return new AppResponse(ProcessUtil.SUCCESS, "AppUser Timezone Update.", payload);
        }
        return new AppResponse(ProcessUtil.ERROR, "Account updated, Email not send contact with support.", payload);
    }

    /**
     * Method use to close appuser account
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse closeAppUserAccount(UpdateUserProfileRequest payload) throws Exception {
        logger.info("Request closeAppUserAccount :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        /**
         * Will update rest of the code
         * close all child
         * close all jobs
         * close all task
         * */
        appUser.get().getAppUserChildren().stream().map(subAppUser -> {
            subAppUser.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
            return subAppUser;
        }).collect(Collectors.toSet());
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendCloseAppUserAccount(payload)) {
            this.notificationService.addNotification(this.getNotificationRequest(
            "Dear user, your account is close."), appUser.get());
            return new AppResponse(ProcessUtil.SUCCESS, "AppUser close.", payload);
        }
        return new AppResponse(ProcessUtil.ERROR, "Account close, Email not send contact with support.", payload);
    }

    /**
     * Method use to get sub appUser account
     * @param username
     * @return AppResponse
     * */
    @Override
    public AppResponse getSubAppUserAccount(String username) throws Exception {
        logger.info("Request getSubAppUserAccount :- " + username);
        if (ProcessUtil.isNull(username)) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            username, APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        AppUserResponse appUserResponse = this.getAppUserDetail(appUser.get());
        appUserResponse.setSubAppUser(
            appUser.get().getAppUserChildren().stream().filter(appUser1 -> {
                return appUser1.getStatus() != APPLICATION_STATUS.DELETE.getLookupValue();
            }).map(appUser1 -> {
                return this.getAppUserDetail(appUser1);
            }).collect(Collectors.toList()));
        return new AppResponse(ProcessUtil.SUCCESS, "AppUser Sub Account.", appUserResponse);
    }

    /**
     * Method use to download template for appuser
     * @return ByteArrayOutputStream
     * */
    @Override
    public ByteArrayOutputStream downloadAppUserTemplateFile() throws Exception {
        String basePath = this.tempStoreDirectory + File.separator;
        ClassLoader cl = this.getClass().getClassLoader();
        InputStream inputStream = cl.getResourceAsStream(this.bulkExcel.BATCH);
        String fileUploadPath = basePath + System.currentTimeMillis() + this.bulkExcel.XLSX_EXTENSION;
        FileOutputStream fileOut = new FileOutputStream(fileUploadPath);
        IOUtils.copy(inputStream, fileOut);
        // after copy the stream into file close
        if (inputStream != null) {
            inputStream.close();
        }
        // 2nd insert data to newly copied file. So that template couldn't be changed.
        XSSFWorkbook workbook = new XSSFWorkbook(new File(fileUploadPath));
        this.bulkExcel.setWb(workbook);
        XSSFSheet sheet = workbook.createSheet(this.bulkExcel.APP_USER);
        this.bulkExcel.setSheet(sheet);
        this.bulkExcel.fillBulkHeader(0, this.bulkExcel.APP_USER_HEADER_FIELD_BATCH_FILE);
        // Priority
        workbook.write(fileOut);
        fileOut.close();
        workbook.close();
        // read the file
        File file = new File(fileUploadPath);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(FileUtils.readFileToByteArray(file));
        file.delete();
        return byteArrayOutputStream;
    }

    /**
     * Method use to download appUser
     * @param payload
     * @return ByteArrayOutputStream
     * */
    @Override
    public ByteArrayOutputStream downloadAppUser(SignupRequest payload) throws Exception {
        logger.info("Request downloadAppUser :- " + payload);
        if (isNull(payload.getAccessUserDetail().getUsername())) {
            throw new Exception("AppUser username missing");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            throw new Exception("AppUser not found");
        }
        XSSFWorkbook workbook = new XSSFWorkbook();
        this.bulkExcel.setWb(workbook);
        XSSFSheet xssfSheet = workbook.createSheet(this.bulkExcel.APP_USER);
        this.bulkExcel.setSheet(xssfSheet);
        AtomicInteger rowCount = new AtomicInteger();
        this.bulkExcel.fillBulkHeader(rowCount.get(), new String[] {
            "FIRSTNAME", "LASTNAME", "TIMEZONE", "USERNAME", "EMAIL"
        });
        appUser.get().getAppUserChildren().stream()
            .filter(tempAppUser -> !tempAppUser.getStatus().equals(APPLICATION_STATUS.DELETE.getLookupValue()))
            .forEach(tempAppUser -> {
                rowCount.getAndIncrement();
                List<String> dataCellValue = new ArrayList<>();
                dataCellValue.add(tempAppUser.getFirstName());
                dataCellValue.add(tempAppUser.getLastName());
                dataCellValue.add(tempAppUser.getTimeZone());
                dataCellValue.add(tempAppUser.getUsername());
                dataCellValue.add(tempAppUser.getEmail());
                this.bulkExcel.fillBulkBody(dataCellValue, rowCount.get());
        });
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        return outputStream;
    }

    /**
     * Method use to upload appUser
     * @param payload
     * @return ByteArrayOutputStream
     * */
    @Override
    public AppResponse uploadAppUser(FileUploadRequest payload) throws Exception {
        logger.info("Request for bulk uploading file!");
        Gson gson = new Gson();
        SignupRequest signupRequest = gson.fromJson((String) payload.getData(), SignupRequest.class);
        if (isNull(signupRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            signupRequest.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        } else if (!payload.getFile().getContentType().equalsIgnoreCase(this.bulkExcel.SHEET_NAME)) {
            logger.info("File Type " + payload.getFile().getContentType());
            return new AppResponse(ProcessUtil.ERROR, "You can upload only .xlsx extension file.");
        }
        // fill the stream with file into work-book
        Optional<LookupData> uploadLimit = this.lookupDataRepository.findByLookupType(LookupDetailUtil.UPLOAD_LIMIT);
        XSSFWorkbook workbook = new XSSFWorkbook(payload.getFile().getInputStream());
        if (isNull(workbook) || workbook.getNumberOfSheets() == 0) {
            return new AppResponse(ProcessUtil.ERROR,  "You uploaded empty file.");
        }
        XSSFSheet sheet = workbook.getSheet(this.bulkExcel.APP_USER);
        if (isNull(sheet)) {
            return new AppResponse(ProcessUtil.ERROR, "Sheet not found with (LookupTemplate)");
        } else if (sheet.getLastRowNum() < 1) {
            return new AppResponse(ProcessUtil.ERROR,  "You can't upload empty file.");
        } else if (sheet.getLastRowNum() > Long.valueOf(uploadLimit.get().getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, String.format("File support %s rows at a time.", uploadLimit.get().getLookupValue()));
        }
        List<AppUserValidation> appUserValidations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Iterator<Row> rows = sheet.iterator();
        while (rows.hasNext()) {
            Row currentRow = rows.next();
            if (currentRow.getRowNum() == 0) {
                for (int i=0; i < this.bulkExcel.APP_USER_HEADER_FIELD_BATCH_FILE.length; i++) {
                    if (!currentRow.getCell(i).getStringCellValue().equals(this.bulkExcel.APP_USER_HEADER_FIELD_BATCH_FILE[i])) {
                        return new AppResponse(ProcessUtil.ERROR, "File at row " + (currentRow.getRowNum() + 1) + " " +
                            this.bulkExcel.APP_USER_HEADER_FIELD_BATCH_FILE[i] + " heading missing.");
                    }
                }
            } else if (currentRow.getRowNum() > 0) {
                AppUserValidation appUserValidation = new AppUserValidation();
                appUserValidation.setRowCounter(currentRow.getRowNum()+1);
                for (int i=0; i < this.bulkExcel.APP_USER_HEADER_FIELD_BATCH_FILE.length; i++) {
                    if (i==0) {
                        appUserValidation.setFirstName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==1) {
                        appUserValidation.setLastName(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==2) {
                        appUserValidation.setTimeZone(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==3) {
                        appUserValidation.setUsername(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==4) {
                        appUserValidation.setEmail(this.bulkExcel.getCellDetail(currentRow, i));
                    } else if (i==5) {
                        appUserValidation.setPassword(this.bulkExcel.getCellDetail(currentRow, i));
                    }
                }
                appUserValidation.isValidLookup();
                Optional<LookupData> isAlreadyExistLookup = this.lookupDataRepository.findByLookupType(appUserValidation.getTimeZone());
                if (!isAlreadyExistLookup.isPresent()) {
                    appUserValidation.setErrorMsg(String.format("TimeZone %s not found at row %s.<br>",
                        appUserValidation.getTimeZone(), appUserValidation.getRowCounter()));
                }
                if (this.appUserRepository.existsByUsername(appUserValidation.getUsername())) {
                    appUserValidation.setErrorMsg(String.format("Username %s is already taken at row %s.<br>",
                        appUserValidation.getUsername(), appUserValidation.getRowCounter()));
                }
                if (this.appUserRepository.existsByEmail(appUserValidation.getEmail())) {
                    appUserValidation.setErrorMsg(String.format("Email %s is already taken at row %s.<br>",
                        appUserValidation.getUsername(), appUserValidation.getRowCounter()));
                }
                if (!isNull(appUserValidation.getErrorMsg())) {
                    errors.add(appUserValidation.getErrorMsg());
                    continue;
                }
                appUserValidations.add(appUserValidation);
            }
        }
        if (errors.size() > 0) {
            return new AppResponse(ProcessUtil.ERROR, String.format("Total %d source jobs invalid.", errors.size()), errors);
        }
        appUserValidations.forEach(appUserValidation -> {
            // save the job and scheduler
            AppUser newAppUser = new AppUser();
            newAppUser.setFirstName(appUserValidation.getFirstName());
            newAppUser.setLastName(appUserValidation.getLastName());
            newAppUser.setEmail(appUserValidation.getEmail());
            newAppUser.setUsername(appUserValidation.getUsername());
            newAppUser.setPassword(this.passwordEncoder.encode(appUserValidation.getPassword()));
            newAppUser.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
            newAppUser.setParentAppUser(appUser.get());
            newAppUser.setTimeZone(appUserValidation.getTimeZone());
            Optional<Role> role = Optional.empty();
            if (signupRequest.getAccessUserDetail().isRootUser()) {
                role = this.roleRepository.findByRoleNameAndStatus(ERole.ROLE_ADMIN.name(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            } else {
                role = this.roleRepository.findByRoleNameAndStatus(ERole.ROLE_USER.name(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            }
            if (role.isPresent()) {
                Set<Role> roleSet = new HashSet<>();
                roleSet.add(role.get());
                newAppUser.setAppUserRoles(roleSet);
            }
            this.appUserRepository.save(newAppUser);
            SignupRequest emailRequest = new SignupRequest();
            emailRequest.setFirstname(newAppUser.getFirstName());
            emailRequest.setLastname(newAppUser.getLastName());
            emailRequest.setEmail(newAppUser.getEmail());
            emailRequest.setTimeZone(newAppUser.getTimeZone());
            emailRequest.setRole(newAppUser.getAppUserRoles().iterator().next().getRoleName());
            this.emailMessagesFactory.sendRegisterUser(emailRequest);
        });
        return new AppResponse(ProcessUtil.SUCCESS, "Data save successfully.");
    }

    /**
     * Method use for signIn appUser
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse signInAppUser(LoginRequest payload) throws Exception {
        // spring auth manager will call user detail service
        Authentication authentication = this.authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(payload.getUsername(), payload.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // get the user detail from authentication
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        // token generate
        String jwtToken = this.jwtUtils.generateJwtToken(userDetails);
        // refresh token generate
        RefreshToken refreshToken = this.refreshTokenService.createRefreshToken(userDetails.getAppUserId());
        return new AppResponse(ProcessUtil.SUCCESS, "User successfully authenticate.",
            new AuthResponse(userDetails.getAppUserId(), jwtToken, refreshToken.getToken(),
            userDetails.getUsername(), userDetails.getEmail(), userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority()).collect(Collectors.toList())));
    }

    /**
     * Method use for signUp appUser as admin
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse signupAppUser(SignupRequest payload) throws Exception {
        logger.info("Request signupAppUser :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        } else if (ProcessUtil.isNull(payload.getPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "Password missing.");
        } else if (ProcessUtil.isNull(payload.getTimeZone())) {
            return new AppResponse(ProcessUtil.ERROR, "TimeZone missing.");
        } else if (this.appUserRepository.existsByUsername(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username is already taken.");
        } else if (this.appUserRepository.existsByEmail(payload.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email is already in use.");
        }
        // check if the username and email exist or not
        AppUser appUser = new AppUser();
        appUser.setFirstName(payload.getFirstname());
        appUser.setLastName(payload.getLastname());
        appUser.setEmail(payload.getEmail());
        appUser.setUsername(payload.getUsername());
        appUser.setPassword(this.passwordEncoder.encode(payload.getPassword()));
        // by default active user no need extra action
        appUser.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        // set the parent user which is master admin
        LookupDataResponse lookupDataResponse = this.lookupDataCacheService
            .getParentLookupById(LookupDetailUtil.SUPER_ADMIN);
        Optional<AppUser> superAdmin = this.appUserRepository.
            findByUsernameAndStatus(lookupDataResponse.getLookupValue(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (superAdmin.isPresent()) {
            appUser.setParentAppUser(superAdmin.get());
        }
        if (!ProcessUtil.isNull(payload.getTimeZone())) {
            appUser.setTimeZone(payload.getTimeZone());
        }
        // register user role default as admin role
        Optional<Role> adminRole = this.roleRepository.findByRoleNameAndStatus(
            ERole.ROLE_ADMIN.name(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (adminRole.isPresent()) {
            Set<Role> roleSet = new HashSet<>();
            roleSet.add(adminRole.get());
            appUser.setAppUserRoles(roleSet);
        }
        // saving process
        this.appUserRepository.save(appUser);
        this.emailMessagesFactory.sendRegisterUser(payload);
        this.appUserEnvRepository.insertAppUserEnv(appUser.getAppUserId().intValue(), superAdmin.get().getAppUserId().intValue());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("User successfully register %s.", appUser.getUsername()));
    }

    /**
     * Method use support to forgot password
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse forgotPassword(ForgotPasswordRequest payload) throws Exception {
        logger.info("Request forgotPassword :- " + payload);
        if (ProcessUtil.isNull(payload.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByEmailAndStatus(
            payload.getEmail(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (appUser.isPresent()) {
            payload.setUsername(appUser.get().getUsername());
            payload.setAppUserId(appUser.get().getAppUserId());
            if (this.emailMessagesFactory.sendForgotPassword(payload)) {
                this.notificationService.addNotification(this.getNotificationRequest(
                    "Dear user, your password reset request send."), appUser.get());
                return new AppResponse(ProcessUtil.SUCCESS, "Email send successfully");
            }
            return new AppResponse(ProcessUtil.ERROR,"Email not send contact with support.");
        }
        return new AppResponse(ProcessUtil.ERROR, "Account not exist.");
    }

    /**
     * Method use to reset app user password
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse resetPassword(PasswordResetRequest payload) throws Exception {
        logger.info("Request resetPassword :- " + payload);
        if (ProcessUtil.isNull(payload.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        } else if (ProcessUtil.isNull(payload.getNewPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "New password missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByEmailAndStatus(
            payload.getEmail(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (appUser.isPresent()) {
            appUser.get().setPassword(this.passwordEncoder.encode(payload.getNewPassword()));
            this.appUserRepository.save(appUser.get());
            if (this.emailMessagesFactory.sendResetPassword(payload)) {
                this.notificationService.addNotification(this.getNotificationRequest(
                "Dear user, your password successfully reset."), appUser.get());
                return new AppResponse(ProcessUtil.SUCCESS, "Email send successfully.");
            }
            return new AppResponse(ProcessUtil.ERROR,"Email not send contact with support.");
        }
        return new AppResponse(ProcessUtil.ERROR, "Account not exist.");
    }

    /**
     * Method generate new token base on refresh token
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse authClamByRefreshToken(TokenRefreshRequest payload) throws Exception {
        AtomicReference<String> requestRefreshToken = new AtomicReference<>(payload.getRefreshToken());
        return this.refreshTokenService.findByToken(requestRefreshToken.get())
            .map(this.refreshTokenService::verifyExpiration)
            .map(appResponse -> {
                if (appResponse.getStatus().equals(ProcessUtil.SUCCESS)) {
                    RefreshToken refreshToken = (RefreshToken) appResponse.getData();
                    requestRefreshToken.set(this.jwtUtils.generateTokenFromUsername(
                        refreshToken.getAppUser().getUsername()));
                }
                return new AppResponse(appResponse.getStatus(), appResponse.getMessage(), requestRefreshToken);
            }).orElse(new AppResponse(ProcessUtil.ERROR, "Token not found", requestRefreshToken));
    }

    /**
     * Method use to delete the token to log Out the session
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse logoutAppUser(TokenRefreshRequest payload) throws Exception {
        return this.refreshTokenService.deleteRefreshToken(payload);
    }

    /**
     * Method use to add/edit app user detail
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addEditAppUserAccount(SignupRequest payload) throws Exception {
        logger.info("Request addEditAppUserAccount :- " + payload);
        if (ProcessUtil.isNull(payload.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        } else if (ProcessUtil.isNull(payload.getTimeZone())) {
            return new AppResponse(ProcessUtil.ERROR, "TimeZone missing.");
        } else if (ProcessUtil.isNull(payload.getAccessUserDetail()) ||
             ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Admin user detail missing.");
        }
        // in new user then need to check
        if (ProcessUtil.isNull(payload.getAppUserId())) {
            if (this.appUserRepository.existsByUsername(payload.getUsername())) {
                return new AppResponse(ProcessUtil.ERROR, "Username is already taken.");
            } else if (this.appUserRepository.existsByEmail(payload.getEmail())) {
                return new AppResponse(ProcessUtil.ERROR, "Email is already in use.");
            } else if (ProcessUtil.isNull(payload.getPassword())) {
                return new AppResponse(ProcessUtil.ERROR, "Password missing.");
            }
        }
        // check if the username and email exist or not
        AppUser appUser = !ProcessUtil.isNull(payload.getAppUserId()) ?
            this.appUserRepository.findByUsernameAndStatus(payload.getUsername(),
                APPLICATION_STATUS.ACTIVE.getLookupValue()).get() : new AppUser();
        appUser.setFirstName(payload.getFirstname());
        appUser.setLastName(payload.getLastname());
        // email and username can't update
        if (ProcessUtil.isNull(payload.getAppUserId())) {
            appUser.setEmail(payload.getEmail());
            appUser.setUsername(payload.getUsername());
            appUser.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
            appUser.setPassword(this.passwordEncoder.encode(payload.getPassword()));
            Optional<Role> role = Optional.empty();
            if (payload.getAccessUserDetail().isRootUser()) {
                role = this.roleRepository.findByRoleNameAndStatus(
                    ERole.ROLE_ADMIN.name(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            } else {
                role = this.roleRepository.findByRoleNameAndStatus(
                    ERole.ROLE_USER.name(), APPLICATION_STATUS.ACTIVE.getLookupValue());
            }
            if (role.isPresent()) {
                Set<Role> roleSet = new HashSet<>();
                roleSet.add(role.get());
                appUser.setAppUserRoles(roleSet);
            }
        } else if (!ProcessUtil.isNull(payload.getStatus())) {
            appUser.setStatus(payload.getStatus());
        }
        appUser.setTimeZone(payload.getTimeZone());
        // set the parent user which is master admin
        Optional<AppUser> superAdmin = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (superAdmin.isPresent()) {
            appUser.setParentAppUser(superAdmin.get());
        } else {
            return new AppResponse(ProcessUtil.ERROR, "Admin user detail missing.");
        }
        // saving process
        this.appUserRepository.save(appUser);
        /**
         * Will update rest of the code
         * close all child
         * close all jobs
         * close all task
         * and update email not send if user inactive and delete
         * */
        if (!ProcessUtil.isNull(payload.getAppUserId())) {
            UpdateUserProfileRequest updateUserProfileRequest = new UpdateUserProfileRequest();
            updateUserProfileRequest.setEmail(payload.getEmail());
            updateUserProfileRequest.setUsername(payload.getUsername());
            updateUserProfileRequest.setFirstName(payload.getFirstname());
            updateUserProfileRequest.setLastName(payload.getLastname());
            this.emailMessagesFactory.sendUpdateAppUserProfile(updateUserProfileRequest);
            this.notificationService.addNotification(this.getNotificationRequest("Dear user, Account profile updated."), appUser);
        } else {
            this.emailMessagesFactory.sendRegisterUser(payload);
            this.notificationService.addNotification(this.getNotificationRequest("Dear user, your account created successfully."), appUser);
            this.appUserEnvRepository.insertAppUserEnv(appUser.getAppUserId().intValue(), superAdmin.get().getAppUserId().intValue());
        }
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Request successfully process %s.", appUser.getUsername()));
    }

    /**
     * Method use to get appuser detail
     * @param appUser
     * */
    private AppUserResponse getAppUserDetail(AppUser appUser) {
        AppUserResponse appUserResponse = new AppUserResponse();
        appUserResponse.setAppUserId(appUser.getAppUserId());
        appUserResponse.setFirstName(appUser.getFirstName());
        appUserResponse.setLastName(appUser.getLastName());
        appUserResponse.setTimeZone(appUser.getTimeZone());
        appUserResponse.setUsername(appUser.getUsername());
        appUserResponse.setEmail(appUser.getEmail());
        appUserResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUser.getStatus()));
        appUserResponse.setDateCreated(appUser.getDateCreated());
        appUserResponse.setRoleResponse(appUser.getAppUserRoles().stream()
            .map(role -> getRoleResponse(role)).collect(Collectors.toSet()));
        return appUserResponse;
    }

    /**
     * Method use to get role response
     * @param role
     * */
    private RoleResponse getRoleResponse(Role role) {
        RoleResponse roleResponse = new RoleResponse();
        roleResponse.setRoleId(role.getRoleId());
        roleResponse.setRoleName(role.getRoleName());
        roleResponse.setStatus(APPLICATION_STATUS.getStatusByValue(role.getStatus()));
        roleResponse.setDateCreated(role.getDateCreated());
        return roleResponse;
    }

    /**
     * Method use to get the notification request
     * @param body
     * */
    private NotificationRequest getNotificationRequest(String body) {
        LookupDataResponse notificationTime = this.lookupDataCacheService
            .getParentLookupById(LookupDetailUtil.NOTIFICATION_DISAPPEAR_TIME);
        NotificationRequest notificationAudit = new NotificationRequest();
        notificationAudit.setBody(body);
        notificationAudit.setNotifyType(NOTIFICATION_TYPE.USER_NOTIFICATION.getLookupValue());
        notificationAudit.setMessageStatus(NOTIFICATION_STATUS.UNREAD.getLookupValue());
        notificationAudit.setExpireTime(ProcessUtil.addDays(new Timestamp(System.currentTimeMillis()),
            Long.valueOf(notificationTime.getLookupValue())));
        return notificationAudit;
    }
}
