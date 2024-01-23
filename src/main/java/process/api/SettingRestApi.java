package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.payload.request.ConfigurationMakerRequest;
import process.model.payload.request.QueryRequest;
import process.model.payload.response.AppResponse;
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
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/dynamicQueryResponse", method = RequestMethod.POST)
    public ResponseEntity<?> dynamicQueryResponse(@RequestBody QueryRequest payload) {
        try {
            return new ResponseEntity<>(this.settingApiService.dynamicQueryResponse(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while dynamicQueryResponse ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to create the xml setting for source task
     * @param payload
     * @return ResponseEntity<?> xmlCreateChecker
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(path = "xmlCreateChecker",  method = RequestMethod.POST)
    public ResponseEntity<?> xmlCreateChecker(@RequestBody ConfigurationMakerRequest payload) {
        try {
            if (payload.getXmlTagsInfo() != null) {
                return new ResponseEntity<>(new AppResponse(ProcessUtil.SUCCESS,
                    this.xmlOutTagInfoUtil.makeXml(payload)), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "Wrong Input"), HttpStatus.OK);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while xmlCreateChecker ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}