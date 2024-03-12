package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.pojo.AppUser;
import process.model.pojo.AppUserEnv;
import process.model.pojo.EnvVariables;
import process.model.repository.AppUserEnvRepository;
import process.model.repository.AppUserRepository;
import process.model.repository.EnvVariablesRepository;
import process.payload.request.AppUserEnvRequest;
import process.payload.response.AppResponse;
import process.payload.response.AppUserEnvResponse;
import process.payload.response.AppUserResponse;
import process.service.EnvService;
import process.util.ProcessUtil;
import process.util.lookuputil.APPLICATION_STATUS;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 */
/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class EnvServiceImpl implements EnvService {

    private Logger logger = LoggerFactory.getLogger(EnvServiceImpl.class);

    @Autowired
    private EnvVariablesRepository envVariablesRepository;
    @Autowired
    private AppUserEnvRepository appUserEnvRepository;
    @Autowired
    private AppUserRepository appUserRepository;


    /***
     * Method use to add env variable
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addEnv(AppUserEnvRequest payload) {
        logger.info("Request addEnv :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getEnvKey())) {
            return new AppResponse(ProcessUtil.ERROR, "Env envKey required.");
        } else if (this.envVariablesRepository.findByEnvKeyAndStatusNot(
            payload.getEnvKey(), APPLICATION_STATUS.DELETE.getLookupValue()).isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env envKey already exist.");
        }
        EnvVariables envVariables = new EnvVariables();
        envVariables.setEnvKey(payload.getEnvKey());
        envVariables.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        envVariables.setCreatedBy(adminUser.get());
        this.envVariablesRepository.save(envVariables);
        AppUserEnv appUserEnv = new AppUserEnv();
        appUserEnv.setAppUser(adminUser.get());
        appUserEnv.setCreatedBy(adminUser.get());
        appUserEnv.setEnvVariables(envVariables);
        appUserEnv.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        this.appUserEnvRepository.save(appUserEnv);
        payload.setEnvKeyId(envVariables.getEnvKeyId());
        payload.setAuEnvId(appUserEnv.getAuEnvId());
        return new AppResponse(ProcessUtil.SUCCESS, "EnvVariables Save", payload);
    }

    /**
     * Method use edit the env
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse editEnv(AppUserEnvRequest payload) {
        logger.info("Request editEnv :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env KeyId required.");
        } else if (ProcessUtil.isNull(payload.getEnvKey())) {
            return new AppResponse(ProcessUtil.ERROR, "Env Key required.");
        }
        Optional<EnvVariables> envVariables = this.envVariablesRepository.findByEnvKeyIdAndStatusNot(
            payload.getEnvKeyId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!envVariables.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env not found.");
        }
        envVariables.get().setEnvKey(payload.getEnvKey());
        this.envVariablesRepository.save(envVariables.get());
        return new AppResponse(ProcessUtil.SUCCESS, "EnvVariables Update", payload);
    }

    /**
     * Method use delete the env
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteEnv(AppUserEnvRequest payload) {
        logger.info("Request deleteEnv :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env KeyId required.");
        }
        Optional<EnvVariables> envVariables = this.envVariablesRepository.findByEnvKeyIdAndStatusNot(
            payload.getEnvKeyId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!envVariables.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env not found.");
        }
        List<AppUserEnv> appUserEnvList = this.appUserEnvRepository.findAllByEnvKeyIdAndStatus(
            envVariables.get().getEnvKeyId(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUserEnvList.isEmpty()) {
            this.appUserEnvRepository.saveAll(appUserEnvList.stream()
            .map(appUserEnv -> {
                appUserEnv.setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
                return appUserEnv;
            }).collect(Collectors.toSet()));
        }
        envVariables.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.envVariablesRepository.save(envVariables.get());
        return new AppResponse(ProcessUtil.SUCCESS, "EnvVariables Delete", payload);
    }

    /**
     * Method use delete the env
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchAllEvn() {
        return new AppResponse(ProcessUtil.SUCCESS, "Data Fetch",
            this.envVariablesRepository.findAllByStatusNot(APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(envVariables -> {
                AppUserEnvResponse appUserEnvResponse = new AppUserEnvResponse();
                appUserEnvResponse.setEnvKeyId(envVariables.getEnvKeyId());
                appUserEnvResponse.setEnvKey(envVariables.getEnvKey());
                appUserEnvResponse.setDateCreated(envVariables.getDateCreated());
                appUserEnvResponse.setStatus(APPLICATION_STATUS.getStatusByValue(envVariables.getStatus()));
                return appUserEnvResponse;
            }).collect(Collectors.toList()));
    }

    /**
     * Method use to get the env variable detail by id
     * @param payload
     * */
    @Override
    public AppResponse fetchEnvById(AppUserEnvRequest payload) {
        if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env keyId not null.");
        }
        Optional<EnvVariables> envVariables = this.envVariablesRepository.findByEnvKeyIdAndStatusNot(
            payload.getEnvKeyId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!envVariables.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env not found.");
        }
        AppUserEnvResponse appUserEnvResponse = new AppUserEnvResponse();
        appUserEnvResponse.setEnvKeyId(envVariables.get().getEnvKeyId());
        appUserEnvResponse.setEnvKey(envVariables.get().getEnvKey());
        appUserEnvResponse.setStatus(APPLICATION_STATUS.getStatusByValue(envVariables.get().getStatus()));
        return new AppResponse(ProcessUtil.SUCCESS, "Data Fetch", appUserEnvResponse);
    }

    /**
     * Method use to get the env variable detail by env key
     * @param payload
     * */
    @Override
    @Deprecated
    public AppResponse fetchUserEnvByEnvKey(AppUserEnvRequest payload) {
        if (ProcessUtil.isNull(payload.getEnvKey())) {
            return new AppResponse(ProcessUtil.ERROR, "Env envKey not null.");
        } else if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> assignUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!assignUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Assign AppUser not found");
        }
        Optional<AppUserEnv> appUserEnv = this.appUserEnvRepository.findByEnvKeyAndAppUserIdAndStatus(payload.getEnvKey(),
            assignUser.get().getAppUserId(),APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUserEnv.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env not found.");
        }
        AppUserEnvResponse appUserEnvResponse = new AppUserEnvResponse();
        appUserEnvResponse.setEnvKey(appUserEnv.get().getEnvVariables().getEnvKey());
        appUserEnvResponse.setEnvValue(appUserEnv.get().getEnvValue());
        appUserEnvResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserEnv.get().getStatus()));
        return new AppResponse(ProcessUtil.SUCCESS, "Data Fetch", appUserEnvResponse);
    }

    /**
     * Method use to link with user
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse linkEnvWithUser(AppUserEnvRequest payload) {
        logger.info("Request linkEnvWithUser :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(payload.getAssignUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Assign Username missing.");
        } else if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env envKeyId required.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        Optional<AppUser> assignUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAssignUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        Optional<EnvVariables> envVariables = this.envVariablesRepository.findByEnvKeyIdAndStatusNot(
            payload.getEnvKeyId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (!assignUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Assign AppUser not found");
        } else if (!envVariables.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Env not found.");
        }
        AppUserEnv appUserEnv = new AppUserEnv();
        appUserEnv.setAppUser(assignUser.get());
        appUserEnv.setCreatedBy(adminUser.get());
        appUserEnv.setEnvVariables(envVariables.get());
        appUserEnv.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        this.appUserEnvRepository.save(appUserEnv);
        return new AppResponse(ProcessUtil.SUCCESS, "AppUserEnv Save", payload);
    }

    /***
     * Method use to fetch the all env by user with env key id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchAllEnvWithEnvKeyId(AppUserEnvRequest payload) {
         if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env KeyId required.");
        }
        return new AppResponse(ProcessUtil.SUCCESS, "AppUserEnv Save", this.appUserEnvRepository.findAllByEnvKeyIdAndStatus(
            payload.getEnvKeyId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).stream().map(appUserEnv -> {
                AppUserEnvResponse appUserEnvResponse = new AppUserEnvResponse();
                appUserEnvResponse.setAuEnvId(appUserEnv.getAuEnvId());
                appUserEnvResponse.setEnvKeyId(appUserEnv.getEnvVariables().getEnvKeyId());
                appUserEnvResponse.setEnvKey(appUserEnv.getEnvVariables().getEnvKey());
                appUserEnvResponse.setEnvValue(appUserEnv.getEnvValue());
                appUserEnvResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserEnv.getStatus()));
                appUserEnvResponse.setAppUser(new AppUserResponse(appUserEnv.getAppUser().getAppUserId(), appUserEnv.getAppUser().getUsername()));
                return appUserEnvResponse;
            }).collect(Collectors.toList()));
    }

    /***
     * Method use to fetch the all env by user with env key id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchAllEnvWithAppUserId(AppUserEnvRequest payload) {
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        return new AppResponse(ProcessUtil.SUCCESS, "AppUserEnv Save", this.appUserEnvRepository.findAllByAppUserIdAndStatus(
            appUser.get().getAppUserId(), APPLICATION_STATUS.ACTIVE.getLookupValue()).stream().map(appUserEnv -> {
            AppUserEnvResponse appUserEnvResponse = new AppUserEnvResponse();
            appUserEnvResponse.setAuEnvId(appUserEnv.getAuEnvId());
            appUserEnvResponse.setEnvKeyId(appUserEnv.getEnvVariables().getEnvKeyId());
            appUserEnvResponse.setEnvKey(appUserEnv.getEnvVariables().getEnvKey());
            appUserEnvResponse.setEnvValue(appUserEnv.getEnvValue());
            appUserEnvResponse.setStatus(APPLICATION_STATUS.getStatusByValue(appUserEnv.getStatus()));
            appUserEnvResponse.setAppUser(new AppUserResponse(appUserEnv.getAppUser().getAppUserId(), appUserEnv.getAppUser().getUsername()));
            return appUserEnvResponse;
        }).collect(Collectors.toList()));
    }

    /***
     * Method use to delete the env by user with env key id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteEnvWithUserId(AppUserEnvRequest payload) {
        if (ProcessUtil.isNull(payload.getEnvKeyId())) {
            return new AppResponse(ProcessUtil.ERROR, "Env KeyId required.");
        } else if (ProcessUtil.isNull(payload.getAccessUserDetail().getAppUserId())) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserId missing.");
        }
        Optional<AppUserEnv> appUserEnvOptional = this.appUserEnvRepository.findByEnvKeyIdAndAppUserIdAndStatusNot(payload.getEnvKeyId(),
            payload.getAccessUserDetail().getAppUserId(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!appUserEnvOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserEnv not found.");
        }
        appUserEnvOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.appUserEnvRepository.save(appUserEnvOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "AppUserEnv delete.");
    }

    /***
     * Method use to update the env variable
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserEnvWithUserId(AppUserEnvRequest payload) {
        if (ProcessUtil.isNull(payload.getAuEnvId())) {
            return new AppResponse(ProcessUtil.ERROR, "AuEnvId required.");
        }
        Optional<AppUserEnv> appUserEnvOptional = this.appUserEnvRepository.findById(payload.getAuEnvId());
        if (!appUserEnvOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUserEnv not found.");
        }
        appUserEnvOptional.get().setEnvValue(payload.getEnvValue());
        this.appUserEnvRepository.save(appUserEnvOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, "AppUserEnv Update.");
    }
}
