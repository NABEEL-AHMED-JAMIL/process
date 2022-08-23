package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.dto.ConfigurationMakerRequest;
import process.model.service.SettingApiService;
import process.util.ProcessUtil;
import process.util.XmlOutTagInfoUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation on setting
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/setting.json")
public class SettingRestApi {

    private Logger logger = LoggerFactory.getLogger(SettingRestApi.class);

    @Autowired
    private SettingApiService settingApiService;
    @Autowired
    private XmlOutTagInfoUtil xmlOutTagInfoUtil;

    /**
     * Integration Status :- done
     * Api use to fetch the app setting
     * (task-type|lookup_data)
     * @return ResponseEntity<?> appSetting
     * */
    @RequestMapping(value = "/appSetting", method = RequestMethod.GET)
    public ResponseEntity<?> appSetting() {
        try {
            return new ResponseEntity<>(this.settingApiService.appSetting(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while appSetting ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to add the sourceTaskType
     * @param tempSourceTaskType
     * @return ResponseEntity<?> addSourceTaskType
     * */
    @RequestMapping(value = "/addSourceTaskType", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTaskType(@RequestBody SourceTaskTypeDto tempSourceTaskType) {
        try {
            return new ResponseEntity<>(this.settingApiService.addSourceTaskType(tempSourceTaskType), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTaskType ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to update the sourceTaskType
     * @param tempSourceTaskType
     * @return ResponseEntity<?> updateSourceTaskType
     * */
    @RequestMapping(value = "/updateSourceTaskType", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceTaskType(@RequestBody SourceTaskTypeDto tempSourceTaskType) {
        try {
            return new ResponseEntity<>(this.settingApiService.updateSourceTaskType(tempSourceTaskType), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTaskType ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to delete the sourceTaskType
     * @param sourceTaskTypeId
     * @return ResponseEntity<?> deleteSourceTaskType
     * */
    @RequestMapping(value = "/deleteSourceTaskType", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSourceTaskType(@RequestParam Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.settingApiService.deleteSourceTaskType(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTaskType ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to add the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> addLookupData
     * */
    @RequestMapping(value = "/addLookupData", method = RequestMethod.POST)
    public ResponseEntity<?> addLookupData(@RequestBody LookupDataDto tempLookupData) {
        try {
            return new ResponseEntity<>(this.settingApiService.addLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to update the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> updateLookupData
     * */
    @RequestMapping(value = "/updateLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> updateLookupData(@RequestBody LookupDataDto tempLookupData) {
        try {
            return new ResponseEntity<>(this.settingApiService.updateLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to create the xml setting for source task
     * @param xlmMakerRequest
     * @return ResponseEntity<?> xmlCreateChecker
     * */
    @RequestMapping(path = "xmlCreateChecker",  method = RequestMethod.POST)
    public ResponseEntity<?> xmlCreateChecker(@RequestBody ConfigurationMakerRequest xlmMakerRequest) {
        try {
            if(xlmMakerRequest.getXmlTagsInfo() != null) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                    this.xmlOutTagInfoUtil.makeXml(xlmMakerRequest)), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Wrong Input"), HttpStatus.OK);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while xmlCreateChecker ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to create the json setting for source task
     * @param jsonMakerRequest
     * @return ResponseEntity<?> jsonCreateChecker
     * */
    @RequestMapping(path = "jsonCreateChecker",  method = RequestMethod.POST)
    public ResponseEntity<?> jsonCreateChecker(@RequestBody ConfigurationMakerRequest jsonMakerRequest) {
        try {
            if(jsonMakerRequest.getJsonTagsInfo() != null) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                    this.xmlOutTagInfoUtil.makeXml(jsonMakerRequest)), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Wrong Input"), HttpStatus.OK);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while jsonCreateChecker ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}
