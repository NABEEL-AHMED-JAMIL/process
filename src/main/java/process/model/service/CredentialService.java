package process.model.service;

import process.model.payload.request.CredentialRequest;
import process.model.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface CredentialService {

    public AppResponse addCredential(CredentialRequest payload) throws Exception ;

    public AppResponse updateCredential(CredentialRequest payload) throws Exception ;

    public AppResponse fetchAllCredential(CredentialRequest payload) throws Exception ;

    public AppResponse fetchCredentialByCredentialId(CredentialRequest payload) throws Exception ;

    public AppResponse deleteCredential(CredentialRequest payload) throws Exception ;

}
