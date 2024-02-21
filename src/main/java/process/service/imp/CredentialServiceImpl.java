package process.service.imp;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.model.pojo.AppUser;
import process.model.pojo.Credential;
import process.model.repository.AppUserRepository;
import process.model.repository.CredentialRepository;
import process.model.repository.STTRepository;
import process.payload.request.CredentialRequest;
import process.payload.response.AppResponse;
import process.payload.response.CredentialResponse;
import process.service.CredentialService;
import process.util.ProcessUtil;
import process.util.lookuputil.CREDENTIAL_TYPE;
import process.util.lookuputil.APPLICATION_STATUS;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class CredentialServiceImpl implements CredentialService {

    private Logger logger = LoggerFactory.getLogger(CredentialServiceImpl.class);

    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private STTRepository sttRepository;
    @Autowired
    private AppUserRepository appUserRepository;

    /**
     * Method use to add the new credential
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addCredential(CredentialRequest payload) throws Exception {
        logger.info("Request addCredential :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getCredentialName())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialName missing.");
        } else if (ProcessUtil.isNull(payload.getCredentialType())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialType missing.");
        } else if (ProcessUtil.isNull(payload.getCredentialContent())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        }
        Credential credential = new Credential();
        credential.setCredentialName(payload.getCredentialName());
        credential.setCredentialType(payload.getCredentialType());
        credential.setCredentialContent(new Gson().toJson(payload.getCredentialContent()));
        credential.setStatus(APPLICATION_STATUS.ACTIVE.getLookupValue());
        credential.setAppUser(adminUser.get());
        this.credentialRepository.save(credential);
        payload.setCredentialId(credential.getCredentialId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Credential save with %d.", payload.getCredentialId()));
    }

    /**
     * Method use to add the new credential
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse updateCredential(CredentialRequest payload) throws Exception {
        logger.info("Request updateCredential :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        } else if (ProcessUtil.isNull(payload.getCredentialName())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialName missing.");
        } else if (ProcessUtil.isNull(payload.getCredentialType())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialType missing.");
        } else if (ProcessUtil.isNull(payload.getCredentialContent())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if (ProcessUtil.isNull(payload.getStatus())) {
            return new AppResponse(ProcessUtil.ERROR, "Status missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatusNotIn(
            payload.getCredentialId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        credentialOptional.get().setCredentialName(payload.getCredentialName());
        credentialOptional.get().setCredentialType(payload.getCredentialType());
        credentialOptional.get().setCredentialContent(new Gson().toJson(payload.getCredentialContent()));
        credentialOptional.get().setStatus(payload.getStatus());
        this.credentialRepository.save(credentialOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Credential save with %d.", payload.getCredentialId()));
    }

    /**
     * Method use to fine all credential
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchAllCredential(CredentialRequest payload) throws Exception {
        logger.info("Request deleteCredential :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<CredentialResponse> credentialResponseList = this.credentialRepository.findAllByUsernameAndStatusNotIn(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue())
            .stream().map(credential -> {
                CredentialResponse credentialResponse = new CredentialResponse();
                credentialResponse.setCredentialId(credential.getCredentialId());
                credentialResponse.setCredentialName(credential.getCredentialName());
                credentialResponse.setCredentialType(CREDENTIAL_TYPE.getFormControlTypeByValue(
                    credential.getCredentialType()));
                credentialResponse.setStatus(APPLICATION_STATUS.getStatusByValue(credential.getStatus()));
                credentialResponse.setDateCreated(credential.getDateCreated());
                return credentialResponse;
            }).collect(Collectors.toList());
        return new AppResponse(SUCCESS, "Data fetch successfully.", credentialResponseList);
    }

    /**
     * Method use to fine credential by id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse fetchCredentialByCredentialId(CredentialRequest payload) throws Exception {
        logger.info("Request fetchCredentialByCredentialId :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatusNotIn(
            payload.getCredentialId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        CredentialResponse credentialResponse = new CredentialResponse();
        credentialResponse.setCredentialId(credentialOptional.get().getCredentialId());
        credentialResponse.setCredentialName(credentialOptional.get().getCredentialName());
        credentialResponse.setCredentialType(CREDENTIAL_TYPE.getFormControlTypeByValue(
            credentialOptional.get().getCredentialType()));
        credentialResponse.setStatus(APPLICATION_STATUS.getStatusByValue(
            credentialOptional.get().getStatus()));
        credentialResponse.setDateCreated(credentialOptional.get().getDateCreated());
        credentialResponse.setCredentialContent(new Gson().fromJson(
            credentialOptional.get().getCredentialContent(), Object.class));
        return new AppResponse(SUCCESS, "Data fetch successfully.", credentialResponse);
    }

    /**
     * Method use to delete credential by id
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse deleteCredential(CredentialRequest payload) throws Exception {
        logger.info("Request deleteCredential :- " + payload);
        if (ProcessUtil.isNull(payload.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(payload.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatusNotIn(
            payload.getCredentialId(), payload.getAccessUserDetail().getUsername(), APPLICATION_STATUS.DELETE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        credentialOptional.get().setStatus(APPLICATION_STATUS.DELETE.getLookupValue());
        this.credentialRepository.save(credentialOptional.get());
        this.sttRepository.unLinkCredentialWithSttByCredentialId(
            credentialOptional.get().getCredentialId(), APPLICATION_STATUS.DELETE.getLookupValue());
        return new AppResponse(SUCCESS, "Data delete successfully.");
    }

}
