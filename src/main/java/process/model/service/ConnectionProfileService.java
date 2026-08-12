package process.model.service;

import process.model.dto.DatabaseConnectionProfileDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface ConnectionProfileService {

    public ResponseDto addConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception;

    public ResponseDto updateConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception;

    public ResponseDto deleteConnectionProfile(Long databaseConnectionProfileId) throws Exception;

    public ResponseDto fetchAllConnectionProfiles() throws Exception;

    public ResponseDto fetchConnectionProfileById(Long databaseConnectionProfileId) throws Exception;

    /** Opens a real connection with the saved (or, for an unsaved draft, the just-typed)
     * credentials and immediately closes it -- proves the profile actually works without
     * persisting anything or returning query data. */
    public ResponseDto testConnection(DatabaseConnectionProfileDto dto) throws Exception;

}
