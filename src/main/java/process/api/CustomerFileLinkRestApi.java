package process.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.Problem;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.customer.CustomerFileReads;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A file's signed link (MIG-334, ADR-025): GET /v1/files/{fileId}/content?token=, as the gateway sends it here with no
 * token and none of its own headers. No caller: the link's signature is the permission, for that one file of that one
 * workspace, for 5 minutes (FileLinks). SecurityConfig and JwtAuthenticationFilter let this one path past the API
 * client check; everything else under /customer still needs an API client's token.
 */
@RestController
@RequestMapping("/customer/files")
public class CustomerFileLinkRestApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CustomerFileReads files;

    public CustomerFileLinkRestApi(CustomerFileReads files) {
        this.files = files;
    }

    /** The file's type as stored; octet-stream when there is none or it is not one. */
    static MediaType mediaTypeOf(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException notAType) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable("fileId") String fileId,
        @RequestParam(value = "token", required = false) String token) {
        CustomerFileReads.Content content = this.files.content(fileId, token);
        if (content.status != 200) {
            byte[] problem;
            try {
                problem = JSON.writeValueAsBytes(content.problem);
            } catch (Exception unwritable) {
                problem = Problem.of(content.status, "The file cannot be read.").toJson().getBytes(StandardCharsets.UTF_8);
            }
            byte[] body = problem;
            return ResponseEntity.status(HttpStatus.valueOf(content.status)).contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE))
                .cacheControl(CacheControl.noStore()).body(out -> out.write(body));
        }
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
            .contentType(mediaTypeOf(content.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(content.name == null ? "file" : content.name, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore());
        if (content.size >= 0) {
            ok.contentLength(content.size);
        }
        InputStream in = content.body;
        return ok.body(out -> {
            try (InputStream bytes = in) {
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = bytes.read(chunk)) != -1) {
                    out.write(chunk, 0, n);
                }
            }
        });
    }
}
