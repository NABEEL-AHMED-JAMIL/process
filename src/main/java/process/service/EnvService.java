package process.service;

import process.payload.request.AppUserEnvRequest;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface EnvService {

    public AppResponse addEnv(AppUserEnvRequest payload);

    public AppResponse editEnv(AppUserEnvRequest payload);

    public AppResponse deleteEnv(AppUserEnvRequest payload);

    public AppResponse fetchAllEvn();

    public AppResponse fetchEnvById(AppUserEnvRequest payload);

    public AppResponse fetchUserEnvByEnvKey(AppUserEnvRequest payload);

    public AppResponse linkEnvWithUser(AppUserEnvRequest payload);

    public AppResponse fetchAllEnvWithEnvKeyId(AppUserEnvRequest payload);

    public AppResponse fetchAllEnvWithAppUserId(AppUserEnvRequest payload);

    public AppResponse deleteEnvWithUserId(AppUserEnvRequest payload);

    public AppResponse updateAppUserEnvWithUserId(AppUserEnvRequest payload);

}
