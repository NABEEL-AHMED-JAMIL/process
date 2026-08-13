package process.model.service;

import process.model.dto.DatabaseConnectionProfileDto;
import process.model.dto.ResponseDto;

public interface ConnectionProfileService {

    public ResponseDto addConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception;

    public ResponseDto updateConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception;

    public ResponseDto deleteConnectionProfile(Long databaseConnectionProfileId) throws Exception;

    public ResponseDto fetchAllConnectionProfiles() throws Exception;

    public ResponseDto fetchConnectionProfileById(Long databaseConnectionProfileId) throws Exception;

    public ResponseDto testConnection(DatabaseConnectionProfileDto dto) throws Exception;

}
