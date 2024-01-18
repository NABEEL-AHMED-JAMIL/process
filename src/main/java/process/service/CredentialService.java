package process.service;

import process.payload.request.CredentialRequest;
import process.payload.response.AppResponse;

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
