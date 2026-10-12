package process.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.customer.CustomerFileReads;
import process.customer.CustomerResponses;

import java.util.Map;

/**
 * Reading a file by id through the customer API (MIG-334, ADR-025), as the gateway sends GET /v1/files/{id} and
 * /v1/files/{id}/meta here (/api/v1/customer/files/...): API clients only, scope files:read. Uploading (POST /v1/files)
 * is storage-service's; the signed link's content is CustomerFileLinkRestApi's.
 */
@RestController
@PreAuthorize("hasRole('API_CLIENT')")
@RequestMapping("/customer/files")
public class CustomerFilesRestApi {

    private final CustomerFileReads files;

    public CustomerFilesRestApi(CustomerFileReads files) {
        this.files = files;
    }

    /** 302 to the file's signed link (5 minutes), with {url, expiresAt}. */
    @GetMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> link(@PathVariable("fileId") String fileId) {
        return CustomerResponses.of(this.files.link(fileId));
    }

    @GetMapping("/{fileId}/meta")
    public ResponseEntity<Map<String, Object>> meta(@PathVariable("fileId") String fileId) {
        return CustomerResponses.of(this.files.meta(fileId));
    }
}
