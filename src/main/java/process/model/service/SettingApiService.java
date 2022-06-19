package process.model.service;

import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;

/**
 * @author Nabeel Ahmed
 */
public interface SettingApiService {

    public ResponseDto appSetting() throws Exception;

    public ResponseDto addSourceTaskType(SourceTaskTypeDto tempSourceTaskType) throws Exception;

    public ResponseDto updateSourceTaskType(SourceTaskTypeDto tempSourceTaskType) throws Exception;

    public ResponseDto addLookupData(LookupDataDto tempLookupData) throws Exception;

    public ResponseDto updateLookupData(LookupDataDto tempLookupData) throws Exception;

}