package process.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import process.forms.FormFields;
import process.forms.PublicForms;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * The one door anyone may knock on without signing in (MIG-278): /publicForm.json/{token} -- read that link's form,
 * upload a file for it, submit it. Everything is decided by {@link PublicForms}; this class reads the request (the
 * address the gateway saw, and a sign-in when the link requires one) and turns a refusal into its HTTP status.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/publicForm.json/{token}")
public class PublicFormRestApi {

    /** The fields a submission sends. */
    public static class SubmitRequest {
        public String ticket;
        public Map<String, Object> answers;
        /** Left empty by a person: the page never shows it. */
        public String website;
    }

    private final PublicForms forms;

    public PublicFormRestApi(PublicForms forms) {
        this.forms = forms;
    }

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> open(@PathVariable String token, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(new ResponseDto(SUCCESS, "Form fetched.", this.forms.open(token, address(request), signedInTenant())));
        } catch (PublicForms.Refused refused) {
            return refusal(refused);
        }
    }

    @RequestMapping(value = "/upload", method = RequestMethod.POST)
    public ResponseEntity<?> upload(@PathVariable String token, @RequestParam String ticket, @RequestParam String field,
        @RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        if (file.getSize() > FormFields.LARGEST_UPLOAD_BYTES) {
            return ResponseEntity.ok(FormRestApi.tooLarge());
        }
        try {
            return ResponseEntity.ok(this.forms.upload(token, ticket, field, file.getOriginalFilename(), file.getBytes(), address(request),
                signedInTenant(), signedInUser()));
        } catch (PublicForms.Refused refused) {
            return refusal(refused);
        }
    }

    @RequestMapping(value = "/submit", method = RequestMethod.POST)
    public ResponseEntity<?> submit(@PathVariable String token, @RequestBody SubmitRequest body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(this.forms.submit(token, body == null ? null : body.ticket, body == null ? null : body.answers,
                body == null ? null : body.website, address(request), signedInTenant(), signedInUser(), signedInUser() == null ? null
                    : TenantContext.getUsername()));
        } catch (PublicForms.Refused refused) {
            return refusal(refused);
        }
    }

    private static ResponseEntity<?> refusal(PublicForms.Refused refused) {
        return new ResponseEntity<>(new ResponseDto(ERROR, refused.getMessage()), refused.status);
    }

    /**
     * The address the gateway saw: the LAST X-Forwarded-For entry (the gateway appends what it saw; anything before it the
     * visitor could have written), else the connection's own.
     */
    static String address(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private static Long signedInTenant() {
        Long tenant = TenantContext.getTenantId();
        return tenant == null || tenant <= 0 ? null : tenant;
    }

    private static Long signedInUser() {
        return signedInTenant() == null ? null : TenantContext.getAppUserId();
    }
}
