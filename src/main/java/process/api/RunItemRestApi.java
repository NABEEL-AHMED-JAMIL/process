package process.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.security.AuditedRead;
import org.barco.platform.security.ReadOnlyList;
import process.security.TenantContext;
import org.barco.platform.security.WorkspaceData;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.pipeline.items.ItemRetries;
import process.pipeline.items.RunItems;

import java.util.Collections;
import java.util.List;

/**
 * The Run Items view (MIG-420), under the prefix the run page already reads -- gated as the run's page (the Source Jobs page,
 * and for its reads the Queue page too: PageKey), so no gateway route is new:
 *
 * <ul>
 *   <li>POST sourceJob.json/runItems: a run's items, filtered (status, a search by item or a value "risk = High", a parent's
 *       sub-items) and paged, with the fields asked for;</li>
 *   <li>GET sourceJob.json/runItems/detail?jobQueueId=&amp;ref=&amp;attempt=: one item -- its steps, why it failed, its first files,
 *       its versions, whether it can be retried;</li>
 *   <li>GET sourceJob.json/runItems/data?jobQueueId=&amp;ref=&amp;stepKey=: its data at one step (masked);</li>
 *   <li>POST sourceJob.json/runItems/files: its files, paged and searched; GET .../file?fileRef=: one, to show or save;</li>
 *   <li>GET sourceJob.json/runItems/zipLink, then GET .../zip?token=: all its files (or one step's) as a zip, through a link
 *       signed after the access check, valid two minutes, for that person, run and item alone;</li>
 *   <li>GET sourceJob.json/runItems/csv: the items table as a CSV (masked);</li>
 *   <li>POST sourceJob.json/runItems/counts: the item counts of a page of runs (the Runs list); GET .../attention: Home's runs
 *       with failed items;</li>
 *   <li>POST sourceJob.json/retryRunItems: retry failed items, whole or from the failed step (the Source Jobs page only).</li>
 * </ul>
 * A run or item that is not the caller's -- another workspace's included -- is 404. The platform administrator's own sign-in
 * reads none of it (@WorkspaceData); a managed session's item, data, file, zip and CSV reads are audited (@AuditedRead), its
 * lists and counts are not (@ReadOnlyList).
 */
@WorkspaceData
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class RunItemRestApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunItems items;
    private final ItemRetries retries;

    public RunItemRestApi(RunItems items, ItemRetries retries) {
        this.items = items;
        this.retries = retries;
    }

    @ReadOnlyList("a run's items")
    @RequestMapping(value = "/sourceJob.json/runItems", method = RequestMethod.POST)
    public ResponseEntity<?> listRunItems(@RequestBody(required = false) RunItems.ListRequest request) {
        return answer(this.items.list(request == null ? new RunItems.ListRequest() : request));
    }

    @AuditedRead(value = "run item", id = {"jobQueueId", "ref", "attempt"})
    @RequestMapping(value = "/sourceJob.json/runItems/detail", method = RequestMethod.GET)
    public ResponseEntity<?> detail(@RequestParam Long jobQueueId, @RequestParam String ref, @RequestParam(required = false) Integer attempt,
        @RequestParam(required = false) String status, @RequestParam(required = false) String search) {
        return answer(this.items.detail(jobQueueId, ref, attempt, status, search));
    }

    @AuditedRead(value = "run item data", id = {"jobQueueId", "ref", "stepKey", "attempt"}, within = "run item", withinId = {"jobQueueId", "ref",
        "attempt"})
    @RequestMapping(value = "/sourceJob.json/runItems/data", method = RequestMethod.GET)
    public ResponseEntity<?> data(@RequestParam Long jobQueueId, @RequestParam String ref, @RequestParam String stepKey,
        @RequestParam(required = false) Integer attempt) {
        return answer(this.items.data(jobQueueId, ref, stepKey, attempt));
    }

    @ReadOnlyList("an item's files")
    @RequestMapping(value = "/sourceJob.json/runItems/files", method = RequestMethod.POST)
    public ResponseEntity<?> listRunItemFiles(@RequestBody(required = false) RunItems.FilesRequest request) {
        return answer(this.items.files(request == null ? new RunItems.FilesRequest() : request));
    }

    @AuditedRead(value = "run item file", id = {"jobQueueId", "ref", "fileRef"})
    @RequestMapping(value = "/sourceJob.json/runItems/file", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> file(@RequestParam Long jobQueueId, @RequestParam String ref, @RequestParam Long fileRef,
        @RequestParam(required = false, defaultValue = "false") boolean inline) throws JsonProcessingException {
        return stream(this.items.file(jobQueueId, ref, fileRef, inline), inline);
    }

    /** A signed link to the item's files as one zip; the link itself opens nothing without the caller's own sign-in. */
    @RequestMapping(value = "/sourceJob.json/runItems/zipLink", method = RequestMethod.GET)
    public ResponseEntity<?> zipLink(@RequestParam Long jobQueueId, @RequestParam String ref, @RequestParam(required = false) String stepKey) {
        return answer(this.items.zipLink(jobQueueId, ref, stepKey == null || stepKey.trim().isEmpty() ? null : stepKey.trim()));
    }

    @AuditedRead(value = "run item files (zip)", id = {"jobQueueId"}, target = false)
    @RequestMapping(value = "/sourceJob.json/runItems/zip", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> zip(@RequestParam Long jobQueueId, @RequestParam String token) throws JsonProcessingException {
        return stream(this.items.zip(jobQueueId, token), false);
    }

    @AuditedRead(value = "run items (CSV)", id = {"jobQueueId"})
    @RequestMapping(value = "/sourceJob.json/runItems/csv", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> csv(@RequestParam Long jobQueueId, @RequestParam(required = false) String status,
        @RequestParam(required = false) String search, @RequestParam(required = false) List<String> fields) throws JsonProcessingException {
        return stream(this.items.csv(jobQueueId, status, search, fields == null ? Collections.<String>emptyList() : fields), false);
    }

    /** Body {jobQueueIds}: the Runs list's page. */
    public static class CountsRequest {
        private List<Long> jobQueueIds;

        public List<Long> getJobQueueIds() { return jobQueueIds; }

        public void setJobQueueIds(List<Long> jobQueueIds) { this.jobQueueIds = jobQueueIds; }
    }

    @ReadOnlyList("item counts of runs")
    @RequestMapping(value = "/sourceJob.json/runItems/counts", method = RequestMethod.POST)
    public ResponseEntity<?> countRunItems(@RequestBody(required = false) CountsRequest request) {
        return answer(this.items.counts(request == null ? null : request.getJobQueueIds()));
    }

    @RequestMapping(value = "/sourceJob.json/runItems/attention", method = RequestMethod.GET)
    public ResponseEntity<?> attention(@RequestParam(required = false) Integer limit) {
        return answer(this.items.attention(limit));
    }

    /** Retry failed items: body {jobQueueId, refs | failed: true, from: "item" | "failed_step"}. The run's owner or an administrator. */
    @RequestMapping(value = "/sourceJob.json/retryRunItems", method = RequestMethod.POST)
    public ResponseEntity<?> retryRunItems(@RequestBody(required = false) ItemRetries.Request request) {
        return answer(this.retries.retry(request, TenantContext.getAppUserId()));
    }

    private static ResponseEntity<?> answer(RunItems.Answer answer) {
        return ResponseEntity.status(answer.status).body(answer.body);
    }

    /** A file streamed, or the refusal as its JSON envelope (its length declared, as runDataset's: MIG-389). */
    private static ResponseEntity<StreamingResponseBody> stream(RunItems.Answer answer, boolean inline) throws JsonProcessingException {
        if (!answer.isFile()) {
            byte[] refusal = JSON.writeValueAsBytes(answer.body);
            return ResponseEntity.status(answer.status).contentType(MediaType.APPLICATION_JSON).contentLength(refusal.length)
                .body(out -> out.write(refusal));
        }
        String name = answer.fileName.replace("\"", "");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment") + "; filename=\"" + name + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .contentType(MediaType.parseMediaType(answer.contentType))
            .body(answer.stream);
    }
}
