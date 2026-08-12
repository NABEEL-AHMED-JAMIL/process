package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.dto.ConfigurationMakerRequest;
import process.model.projection.ItemResponse;
import process.model.service.SettingService;
import process.util.ProcessUtil;
import process.util.XmlOutTagInfoUtil;

/**
 * Api use to perform crud operation on setting. Role policy: settings (source task types,
 * lookups, kafka config surfaced elsewhere) are tenant configuration, so this whole controller
 * requires TENANT_ADMIN+ -- dynamicQueryResponse tightens further to PLATFORM_ADMIN-only inside
 * its own method body (raw native SQL bypasses the tenant Hibernate filter, see its javadoc).
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/setting.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SettingRestApi {

    private Logger logger = LoggerFactory.getLogger(SettingRestApi.class);

    private final SettingService settingService;
    private final XmlOutTagInfoUtil xmlOutTagInfoUtil;

    public SettingRestApi(SettingService settingService,
        XmlOutTagInfoUtil xmlOutTagInfoUtil) {
        this.settingService = settingService;
        this.xmlOutTagInfoUtil = xmlOutTagInfoUtil;
    }

    /**
     * Api use to fetch the app setting
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/dynamicQueryResponse", method = RequestMethod.POST)
    public ResponseEntity<?> dynamicQueryResponse(
        @RequestBody ItemResponse itemResponse) {
        try {
            return new ResponseEntity<>(this.settingService.dynamicQueryResponse(itemResponse), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while dynamicQueryResponse ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch the app setting
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/appSetting", method = RequestMethod.GET)
    public ResponseEntity<?> appSetting() {
        try {
            return new ResponseEntity<>(this.settingService.appSetting(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while appSetting ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to add the sourceTaskType
     * @param tempSourceTaskType
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addSourceTaskType", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTaskType(
        @RequestBody SourceTaskTypeDto tempSourceTaskType) {
        try {
            return new ResponseEntity<>(this.settingService.addSourceTaskType(tempSourceTaskType), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTaskType ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update the sourceTaskType
     * @param tempSourceTaskType
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateSourceTaskType", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceTaskType(
        @RequestBody SourceTaskTypeDto tempSourceTaskType) {
        try {
            return new ResponseEntity<>(this.settingService.updateSourceTaskType(tempSourceTaskType), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTaskType ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to delete the sourceTaskType
     * @param sourceTaskTypeId
     * @return ResponseEntity<?> deleteSourceTaskType
     * */
    @RequestMapping(value = "/deleteSourceTaskType", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSourceTaskType(
        @RequestParam Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.settingService.deleteSourceTaskType(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTaskType ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch the caller's own tenant Kafka routing override for a source task type
     * @param sourceTaskTypeId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchKafkaRoute", method = RequestMethod.GET)
    public ResponseEntity<?> fetchKafkaRoute(@RequestParam Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.settingService.fetchKafkaRoute(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchKafkaRoute ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to create/replace the caller's own tenant Kafka routing override for a source
     * task type
     * @param sourceTaskTypeId
     * @param kafkaConnectionProfileId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/setKafkaRoute", method = RequestMethod.PUT)
    public ResponseEntity<?> setKafkaRoute(@RequestParam Long sourceTaskTypeId, @RequestParam Long kafkaConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.settingService.setKafkaRoute(sourceTaskTypeId, kafkaConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while setKafkaRoute ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to remove the caller's own tenant Kafka routing override for a source task type
     * @param sourceTaskTypeId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteKafkaRoute", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteKafkaRoute(@RequestParam Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.settingService.deleteKafkaRoute(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteKafkaRoute ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to add the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> addLookupData
     * */
    @RequestMapping(value = "/addLookupData", method = RequestMethod.POST)
    public ResponseEntity<?> addLookupData(
        @RequestBody LookupDataDto tempLookupData) {
        try {
            return new ResponseEntity<>(this.settingService.addLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLookupData ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> updateLookupData
     * */
    @RequestMapping(value = "/updateLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> updateLookupData(
        @RequestBody LookupDataDto tempLookupData) {
        try {
            return new ResponseEntity<>(this.settingService.updateLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateLookupData ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch the sub-Lookup by parent lookup id
     * @param parentLookUpId
     * @return ResponseEntity<?> fetchSubLookupByParentId
     * */
    @RequestMapping(value = "/fetchSubLookupByParentId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubLookupByParentId(
        @RequestParam Long parentLookUpId) {
        try {
            return new ResponseEntity<>(this.settingService.fetchSubLookupByParentId(parentLookUpId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubLookupByParentId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to delete the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> deleteLookupData
     * */
    @RequestMapping(value = "/deleteLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteLookupData(
        @RequestBody LookupDataDto tempLookupData) {
        try {
            return new ResponseEntity<>(this.settingService.deleteLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteLookupData ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to create the xml setting for source task
     * @param xlmMakerRequest
     * @return ResponseEntity<?> xmlCreateChecker
     * */
    @RequestMapping(path = "xmlCreateChecker",  method = RequestMethod.POST)
    public ResponseEntity<?> xmlCreateChecker(
        @RequestBody ConfigurationMakerRequest xlmMakerRequest) {
        try {
            if(xlmMakerRequest.getXmlTagsInfo() != null) {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                    this.xmlOutTagInfoUtil.makeXml(xlmMakerRequest)), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Wrong Input"), HttpStatus.OK);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while xmlCreateChecker ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}