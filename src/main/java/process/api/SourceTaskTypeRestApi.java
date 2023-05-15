package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.*;
import process.payload.response.AppResponse;
import process.service.SourceTaskTypeService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * Api use to perform crud operation
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/sourceTaskType.json")
public class SourceTaskTypeRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceTaskTypeRestApi.class);

    @Autowired
    private SourceTaskTypeService sourceTaskTypeService;

    /**
     * api-status :- done
     * @apiName :- addSTT
     * @apiNote :- Api use to create stt (source task type)
     * @param sttRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTT", method = RequestMethod.POST)
    public ResponseEntity<?> addSTT(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTT(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- editSTT
     * @apiNote :- Api use to update stt (source task type)
     * @param sttRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTT", method = RequestMethod.POST)
    public ResponseEntity<?> editSTT(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTT(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTT
     * @apiNote :- Api use to delete stt (source task type)
     * @param sttRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTT", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTT(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTT(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTBySttId
     * @apiNote :- Api use to fetch stt by stt id(source task type)
     * @param sttRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTBySttId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTBySttId(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTBySttId(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTT
     * @apiNote :- Api use to fetch stt(source task type)
     * @param sttRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTT", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTT(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTT(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTLinkUser
     * @apiNote :- Api use to link stt with user
     * @param sttLinkUserRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTLinkUser(@RequestBody STTLinkUserRequest sttLinkUserRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTLinkUser(sttLinkUserRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTLinkUser
     * @apiNote :- Api use to de-link stt with user
     * @param sttLinkUserRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTLinkUser(@RequestBody STTLinkUserRequest sttLinkUserRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTLinkUser(sttLinkUserRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTLinkUser
     * @apiNote :- Api use to fetch link stt with user
     * @param sttLinkUserRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTLinkUser(@RequestBody STTLinkUserRequest sttLinkUserRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTLinkUser(sttLinkUserRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTF
     * @apiNote :- Api use to add sttf(source task type form)
     * @param sttFormRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTF(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTF(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- editSTTF
     * @apiNote :- Api use to edit sttf(source task type form)
     * @param sttFormRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTF(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTF(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTF
     * @apiNote :- Api use to delete sttf(source task type form)
     * @param sttFormRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTF(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTF(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTFBySttfId
     * @apiNote :- Api use to fetch sttf with sttf id(source task type form)
     * @param sttFormRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFBySttfId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTFBySttfId(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFBySttfId(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTFBySttfId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                 "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTF
     * @apiNote :- Api use to fetch sttf(source task type form)
     * @param sttFormRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTF(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTF(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTFLinkSTT
     * @apiNote :- Api use to link sttf with stt
     * @param sttfLinkSTTRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTFLinkSTT(@RequestBody STTFLinkSTTRequest sttfLinkSTTRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTFLinkSTT(sttfLinkSTTRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTFLinkSTT
     * @apiNote :- Api use to de-link sttf with stt
     * @param sttfLinkSTTRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTFLinkSTT(@RequestBody STTFLinkSTTRequest sttfLinkSTTRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTFLinkSTT(sttfLinkSTTRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTFLinkSTT
     * @apiNote :- Api use to fetch link sttf with stt
     * @param sttfLinkSTTRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTFLinkSTT(@RequestBody STTFLinkSTTRequest sttfLinkSTTRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFLinkSTT(sttfLinkSTTRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTS
     * @apiNote :- Api use to add stts(source task type section)
     * @param sttSectionRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTS(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTS(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- editSTTS
     * @apiNote :- Api use to edit stts(source task type section)
     * @param sttSectionRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTS(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTS(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTS
     * @apiNote :- Api use to delete stts(source task type section)
     * @param sttSectionRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTS(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTS(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTSBySttsId
     * @apiNote :- Api use to fetch stts by id(source task type section)
     * @param sttSectionRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTSBySttsId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTSBySttsId(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTSBySttsId(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTSBySttsId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTS
     * @apiNote :- Api use to fetch stts
     * @param sttSectionRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTS(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTS(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTSLinkSTTF
     * @apiNote :- Api use to link stts with sttf
     * @param sttsLinkSTTFRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest sttsLinkSTTFRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTSLinkSTTF(sttsLinkSTTFRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTSLinkSTTF
     * @apiNote :- Api use to de-link stts with sttf
     * @param sttsLinkSTTFRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest sttsLinkSTTFRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTSLinkSTTF(sttsLinkSTTFRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTSLinkSTTF
     * @apiNote :- Api use to fetch link stts with sttf
     * @param sttsLinkSTTFRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest sttsLinkSTTFRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTSLinkSTTF(sttsLinkSTTFRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTC
     * @apiNote :- Api use to add sttc(source task type control)
     * @param sttControlRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTC(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTC(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- editSTTC
     * @apiNote :- Api use to edit sttc(source task type control)
     * @param sttControlRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTC(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTC(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTC
     * @apiNote :- Api use to delete sttc(source task type control)
     * @param sttControlRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTC(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTC(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTCBySttcId
     * @apiNote :- Api use to fetch sttc(source task type control) by sttc id
     * @param sttControlRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTCBySttcId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTCBySttcId(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTCBySttcId(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTCBySttcId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTC
     * @apiNote :- Api use to fetch sttc(source task type control)
     * @param sttControlRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTC(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTC(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTCLinkSTTS
     * @apiNote :- Api use to link sttc with stts
     * @param sttcLinkSTTSRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest sttcLinkSTTSRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTCLinkSTTS(sttcLinkSTTSRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteSTTCLinkSTTS
     * @apiNote :- Api use to de-link sttc with stts
     * @param sttcLinkSTTSRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest sttcLinkSTTSRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTCLinkSTTS(sttcLinkSTTSRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSTTCLinkSTTS
     * @apiNote :- Api use to fetch link sttc with stts
     * @param sttcLinkSTTSRequest
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest sttcLinkSTTSRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTCLinkSTTS(sttcLinkSTTSRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- downloadSTTCommonTemplateFile
     * @apiNote :- Api use to download sttc template file
     * @param sttFileUReq
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommonTemplateFile", method = RequestMethod.POST)
    public ResponseEntity<?> downloadSTTCommonTemplateFile(@RequestBody STTFileUploadRequest sttFileUReq) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchSTTDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(
                this.sourceTaskTypeService.downloadSTTCommonTemplateFile(sttFileUReq).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommonTemplateFile xlsx file", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- downloadSTTCommon
     * @apiNote :- Api use to download stt* all file
     * @param sttFileUReq
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> downloadSTTCommon(@RequestBody STTFileUploadRequest sttFileUReq) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceTaskTypeService.downloadSTTCommon(sttFileUReq).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommon ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- uploadSTTCommon
     * @apiNote :- Api use to upload
     * @param fileObject
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/uploadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSTTCommon(FileUploadRequest fileObject) {
        try {
            if (!ProcessUtil.isNull(fileObject.getFile())) {
                return new ResponseEntity<>(this.sourceTaskTypeService.uploadSTTCommon(fileObject), HttpStatus.OK);
            }
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSTTCommon ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
