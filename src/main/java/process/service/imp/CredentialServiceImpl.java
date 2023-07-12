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
import process.util.lookuputil.CredentialType;
import process.util.lookuputil.Status;
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


    @Override
    public AppResponse addCredential(CredentialRequest credentialRequest) throws Exception {
        logger.info("Request addCredential :- " + credentialRequest);
        if (isNull(credentialRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            credentialRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialName())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialName missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialType())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialType missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialContent())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        }
        Credential credential = new Credential();
        credential.setCredentialName(credentialRequest.getCredentialName());
        credential.setCredentialType(credentialRequest.getCredentialType());
        credential.setCredentialContent(
            new Gson().toJson(credentialRequest.getCredentialContent()));
        credential.setStatus(Status.ACTIVE.getLookupValue());
        credential.setAppUser(adminUser.get());
        this.credentialRepository.save(credential);
        credentialRequest.setCredentialId(credential.getCredentialId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "Credential save with %d.", credentialRequest.getCredentialId()));
    }

    @Override
    public AppResponse updateCredential(CredentialRequest credentialRequest) throws Exception {
        logger.info("Request updateCredential :- " + credentialRequest);
        if (isNull(credentialRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            credentialRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialName())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialName missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialType())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialType missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialContent())) {
            return new AppResponse(ProcessUtil.ERROR, "TaskType missing.");
        } else if (ProcessUtil.isNull(credentialRequest.getStatus())) {
            return new AppResponse(ProcessUtil.ERROR, "Status missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatus(
            credentialRequest.getCredentialId(), credentialRequest.getAccessUserDetail().getUsername(),
            Status.ACTIVE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        credentialOptional.get().setCredentialName(credentialRequest.getCredentialName());
        credentialOptional.get().setCredentialType(credentialRequest.getCredentialType());
        credentialOptional.get().setCredentialContent(new Gson().toJson(credentialRequest.getCredentialContent()));
        credentialOptional.get().setStatus(credentialRequest.getStatus());
        this.credentialRepository.save(credentialOptional.get());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("Credential save with %d.", credentialRequest.getCredentialId()));
    }

    @Override
    public AppResponse fetchAllCredential(CredentialRequest credentialRequest) throws Exception {
        logger.info("Request deleteCredential :- " + credentialRequest);
        if (isNull(credentialRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            credentialRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        }
        List<CredentialResponse> credentialResponseList = this.credentialRepository.findAllByUsernameAndStatusNotIn(
            credentialRequest.getAccessUserDetail().getUsername(), Status.DELETE.getLookupValue())
            .stream().map(credential -> {
                CredentialResponse credentialResponse = new CredentialResponse();
                credentialResponse.setCredentialId(credential.getCredentialId());
                credentialResponse.setCredentialName(credential.getCredentialName());
                credentialResponse.setCredentialType(CredentialType.getFormControlTypeByValue(
                    credential.getCredentialType()));
                credentialResponse.setStatus(Status.getStatusByValue(credential.getStatus()));
                credentialResponse.setDateCreated(credential.getDateCreated());
                return credentialResponse;
            }).collect(Collectors.toList());
        return new AppResponse(SUCCESS, "Data fetch successfully.", credentialResponseList);
    }

    @Override
    public AppResponse fetchCredentialByCredentialId(CredentialRequest credentialRequest) throws Exception {
        logger.info("Request fetchCredentialByCredentialId :- " + credentialRequest);
        if (isNull(credentialRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            credentialRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatusNotIn(
            credentialRequest.getCredentialId(), credentialRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        CredentialResponse credentialResponse = new CredentialResponse();
        credentialResponse.setCredentialId(credentialOptional.get().getCredentialId());
        credentialResponse.setCredentialName(credentialOptional.get().getCredentialName());
        credentialResponse.setCredentialType(CredentialType.getFormControlTypeByValue(
            credentialOptional.get().getCredentialType()));
        credentialResponse.setStatus(Status.getStatusByValue(
            credentialOptional.get().getStatus()));
        credentialResponse.setDateCreated(credentialOptional.get().getDateCreated());
        credentialResponse.setCredentialContent(new Gson().fromJson(
            credentialOptional.get().getCredentialContent(), Object.class));
        return new AppResponse(SUCCESS, "Data fetch successfully.", credentialResponse);
    }

    @Override
    public AppResponse deleteCredential(CredentialRequest credentialRequest) throws Exception {
        logger.info("Request deleteCredential :- " + credentialRequest);
        if (isNull(credentialRequest.getAccessUserDetail().getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> adminUser = this.appUserRepository.findByUsernameAndStatus(
            credentialRequest.getAccessUserDetail().getUsername(), Status.ACTIVE.getLookupValue());
        if (!adminUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found");
        } else if (ProcessUtil.isNull(credentialRequest.getCredentialId())) {
            return new AppResponse(ProcessUtil.ERROR, "CredentialId missing.");
        }
        Optional<Credential> credentialOptional = this.credentialRepository.findByCredentialIdAndUsernameAndStatusNotIn(
            credentialRequest.getCredentialId(), credentialRequest.getAccessUserDetail().getUsername(),
            Status.DELETE.getLookupValue());
        if (!credentialOptional.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "Credential not found");
        }
        credentialOptional.get().setStatus(Status.DELETE.getLookupValue());
        this.credentialRepository.save(credentialOptional.get());
        this.sttRepository.unLinkCredentialWithSttByCredentialId(
            credentialOptional.get().getCredentialId(), Status.DELETE.getLookupValue());
        return new AppResponse(SUCCESS, "Data delete successfully.");
    }

}
