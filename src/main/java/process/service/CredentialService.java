package process.service;

import process.payload.request.CredentialRequest;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface CredentialService {

    public AppResponse addCredential(CredentialRequest credentialRequest) throws Exception ;

    public AppResponse updateCredential(CredentialRequest credentialRequest) throws Exception ;

    public AppResponse fetchAllCredential(CredentialRequest credentialRequest) throws Exception ;

    public AppResponse fetchCredentialByCredentialId(CredentialRequest credentialRequest) throws Exception ;

    public AppResponse deleteCredential(CredentialRequest credentialRequest) throws Exception ;

}
