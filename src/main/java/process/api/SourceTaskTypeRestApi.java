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
     * @apiName :- fetchSourceTaskType
     * @apiNote :- Api use to fetch link stt with user
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSourceTaskType", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSourceTaskType(@RequestBody STTLinkUserRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSourceTaskType(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSourceTaskType ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                    "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTT
     * @apiNote :- Api use to create stt (source task type)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTT", method = RequestMethod.POST)
    public ResponseEntity<?> addSTT(@RequestBody STTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- editSTT
     * @apiNote :- Api use to update stt (source task type)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTT", method = RequestMethod.POST)
    public ResponseEntity<?> editSTT(@RequestBody STTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTT
     * @apiNote :- Api use to delete stt (source task type)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTT", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTT(@RequestBody STTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTBySttId
     * @apiNote :- Api use to fetch stt by stt id(source task type)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTBySttId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTBySttId(@RequestBody STTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTBySttId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTBySttId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTT
     * @apiNote :- Api use to fetch stt(source task type)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTT", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTT(@RequestBody STTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTLinkUser
     * @apiNote :- Api use to link stt with user
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTLinkUser(@RequestBody STTLinkUserRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTLinkUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTLinkUser
     * @apiNote :- Api use to de-link stt with user
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTLinkUser(@RequestBody STTLinkUserRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTLinkUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTLinkUser
     * @apiNote :- Api use to fetch link stt with user
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTLinkUser", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTLinkUser(@RequestBody STTLinkUserRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTLinkUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTLinkUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTLinkSTTF
     * @apiNote :- Api use to fetch link stt with sttf
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTLinkSTTF(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }


    /**
     * @apiName :- deleteSTTLinkSTTF
     * @apiNote :- Api use to fetch link stt with sttf
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTLinkSTTF(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTLinkUser
     * @apiNote :- Api use to fetch link stt with user
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTLinkSTTF(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTLinkSTTF ", ExceptionUtil.getRootCause(ex));
        return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTF
     * @apiNote :- Api use to add sttf(source task type form)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTF(@RequestBody STTFormRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- editSTTF
     * @apiNote :- Api use to edit sttf(source task type form)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTF(@RequestBody STTFormRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTF
     * @apiNote :- Api use to delete sttf(source task type form)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTF(@RequestBody STTFormRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTFBySttfId
     * @apiNote :- Api use to fetch sttf with sttf id(source task type form)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFBySttfId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTFBySttfId(@RequestBody STTFormRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFBySttfId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTFBySttfId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
             "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTF
     * @apiNote :- Api use to fetch sttf(source task type form)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTF(@RequestBody STTFormRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTFLinkSTT
     * @apiNote :- Api use to link sttf with stt
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTFLinkSTT(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTFLinkSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTFLinkSTT
     * @apiNote :- Api use to de-link sttf with stt
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTFLinkSTT(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTFLinkSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTFLinkSTT
     * @apiNote :- Api use to fetch link sttf with stt
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFLinkSTT", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTFLinkSTT(@RequestBody STTFLinkSTTRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFLinkSTT(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTFLinkSTT ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTFLinkSTTS
     * @apiNote :- Api use to fetch link sttf with stt
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTFLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTFLinkSTTS(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTFLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTFLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTFLinkSTTS
     * @apiNote :- Api use to fetch link sttf with stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTFLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTFLinkSTTS(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTFLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTFLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTFLinkSTTS
     * @apiNote :- Api use to fetch link sttf with stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTFLinkSTTS(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTFLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- addSTTS
     * @apiNote :- Api use to add stts(source task type section)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTS(@RequestBody STTSectionRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- editSTTS
     * @apiNote :- Api use to edit stts(source task type section)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTS(@RequestBody STTSectionRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTS
     * @apiNote :- Api use to delete stts(source task type section)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTS(@RequestBody STTSectionRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTSBySttsId
     * @apiNote :- Api use to fetch stts by id(source task type section)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTSBySttsId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTSBySttsId(@RequestBody STTSectionRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTSBySttsId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTSBySttsId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTS
     * @apiNote :- Api use to fetch stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTS(@RequestBody STTSectionRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTSLinkSTTF
     * @apiNote :- Api use to link stts with sttf
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTSLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTSLinkSTTF
     * @apiNote :- Api use to de-link stts with sttf
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTSLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTSLinkSTTF
     * @apiNote :- Api use to fetch link stts with sttf
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTSLinkSTTF", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTSLinkSTTF(@RequestBody STTSLinkSTTFRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTSLinkSTTF(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTSLinkSTTF ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTSLinkSTTC
     * @apiNote :- Api use to fetch link stts with sttc
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTSLinkSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTSLinkSTTC(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTSLinkSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTSLinkSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTSLinkSTTC
     * @apiNote :- Api use to fetch link stts with sttc
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTSLinkSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTSLinkSTTC(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTSLinkSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTSLinkSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTSLinkSTTC
     * @apiNote :- Api use to fetch link stts with sttc
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTSLinkSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTSLinkSTTC(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTSLinkSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTSLinkSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTC
     * @apiNote :- Api use to add sttc(source task type control)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTC(@RequestBody STTControlRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- editSTTC
     * @apiNote :- Api use to edit sttc(source task type control)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> editSTTC(@RequestBody STTControlRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.editSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTC
     * @apiNote :- Api use to delete sttc(source task type control)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTC(@RequestBody STTControlRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTCBySttcId
     * @apiNote :- Api use to fetch sttc(source task type control) by sttc id
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTCBySttcId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTCBySttcId(@RequestBody STTControlRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTCBySttcId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTCBySttcId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTC
     * @apiNote :- Api use to fetch sttc(source task type control)
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTC", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTC(@RequestBody STTControlRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTC(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTC ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTCLinkSTTS
     * @apiNote :- Api use to link sttc with stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTCLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTCLinkSTTS
     * @apiNote :- Api use to de-link sttc with stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTCLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTCLinkSTTS
     * @apiNote :- Api use to fetch link sttc with stts
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTCLinkSTTS", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSTTCLinkSTTS(@RequestBody STTCLinkSTTSRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTCLinkSTTS(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTCLinkSTTS ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSTTFormDetail
     * @apiNote :- Api use to fetch sttf detail
     * @param formId
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSTTFormDetail", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSTTFormDetail(@RequestParam Long formId) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.fetchSTTFormDetail(formId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSTTDetail ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- addSTTCInteractions
     * @apiNote :- Api use to link interaction
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSTTCInteractions", method = RequestMethod.POST)
    public ResponseEntity<?> addSTTCInteractions(@RequestBody STTCInteractionsRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.addSTTCInteractions(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSTTCInteractions ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteSTTCInteractions
     * @apiNote :- Api use to unlink interaction
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSTTCInteractions", method = RequestMethod.POST)
    public ResponseEntity<?> deleteSTTCInteractions(@RequestBody STTCInteractionsRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.deleteSTTCInteractions(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSTTCInteractions ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- downloadSTTCommonTemplateFile
     * @apiNote :- Api use to download sttc template file
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommonTemplateFile", method = RequestMethod.POST)
    public ResponseEntity<?> downloadSTTCommonTemplateFile(@RequestBody STTFileUploadRequest payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchSTTDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(
                this.sourceTaskTypeService.downloadSTTCommonTemplateFile(payload).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommonTemplateFile xlsx file", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- downloadSTTCommon
     * @apiNote :- Api use to download stt* all file
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> downloadSTTCommon(@RequestBody STTFileUploadRequest payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceTaskTypeService.downloadSTTCommon(payload).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommon ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- uploadSTTCommon
     * @apiNote :- Api use to upload
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/uploadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSTTCommon(FileUploadRequest payload) {
        try {
            if (!ProcessUtil.isNull(payload.getFile())) {
                return new ResponseEntity<>(this.sourceTaskTypeService.uploadSTTCommon(payload), HttpStatus.OK);
            }
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSTTCommon ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
