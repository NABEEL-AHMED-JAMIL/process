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

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/linkSTTWithAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> linkSTTWithAppUser(@RequestBody STTRequest sttRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.linkSTTWithAppUser(sttRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while linkSTTWithAppUser ", ExceptionUtil.getRootCause(ex));
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

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/linkSTTFWithFrom", method = RequestMethod.POST)
    public ResponseEntity<?> linkSTTFWithFrom(@RequestBody STTFormRequest sttFormRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.linkSTTFWithFrom(sttFormRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while linkSTTFWithFrom ", ExceptionUtil.getRootCause(ex));
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
     * @apiName :- editSTTS
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

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/linkSTTSWithFrom", method = RequestMethod.POST)
    public ResponseEntity<?> linkSTTSWithFrom(@RequestBody STTSectionRequest sttSectionRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.linkSTTSWithFrom(sttSectionRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while linkSTTSWithFrom ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    // STTC
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

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/linkSTTCWithFrom", method = RequestMethod.POST)
    public ResponseEntity<?> linkSTTCWithFrom(@RequestBody STTControlRequest sttControlRequest) {
        try {
            return new ResponseEntity<>(this.sourceTaskTypeService.linkSTTCWithFrom(sttControlRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while linkSTTCWithFrom ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    // bath
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommonTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSTTCommonTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchSTTDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(null);
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommonTemplateFile xlsx file", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> downloadSTTCommon(@RequestBody LookupDataRequest tempLookupData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(null);
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSTTCommon ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/uploadSTTCommon", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSTTCommon(FileUploadRequest fileObject) {
        try {
            if (fileObject.getFile() != null) {
                return new ResponseEntity<>(null, HttpStatus.OK);
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
