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
import process.pipeline.items.ItemsAcrossRuns;
import process.pipeline.items.RunItemReviews;
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
 *   <li>POST sourceJob.json/retryRunItems: retry failed items, whole or from the failed step (the Source Jobs page only);
 *       MIG-421: of a bulk selection, only its failed items.</li>
 * </ul>
 * MIG-421 (v2):
 * <ul>
 *   <li>POST sourceJob.json/runItems/selection: what a bulk selection names (the refs ticked, or all the filter and search
 *       find, across pages): how many, how many failed;</li>
 *   <li>GET sourceJob.json/runItems/zipLink with refs (or all, status, search, except): one signed link to the selected items'
 *       files, a folder each; GET .../csv with refs or except: the selection's CSV;</li>
 *   <li>POST sourceJob.json/reviewRunItems: the selected items to a team or role as a Task inbox task (the Source Jobs page);</li>
 *   <li>POST sourceJob.json/itemRuns: a schedule's items across its runs (the Schedules page only); GET
 *       sourceJob.json/itemRuns/compare: one item in two runs, step by step, and the pipeline change between them.</li>
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
    private final ItemsAcrossRuns across;
    private final RunItemReviews reviews;

    public RunItemRestApi(RunItems items, ItemRetries retries, ItemsAcrossRuns across, RunItemReviews reviews) {
        this.items = items;
        this.retries = retries;
        this.across = across;
        this.reviews = reviews;
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

    /**
     * A signed link to the item's files as one zip; the link itself opens nothing without the caller's own sign-in. MIG-421:
     * with refs (the items ticked) or all (every item the status and search find, less except) instead of ref, one link to
     * the selected items' files, a folder each.
     */
    @RequestMapping(value = "/sourceJob.json/runItems/zipLink", method = RequestMethod.GET)
    public ResponseEntity<?> zipLink(@RequestParam Long jobQueueId, @RequestParam(required = false) String ref,
        @RequestParam(required = false) String stepKey, @RequestParam(required = false) List<String> refs,
        @RequestParam(required = false, defaultValue = "false") boolean all, @RequestParam(required = false) String status,
        @RequestParam(required = false) String search, @RequestParam(required = false) List<String> except) {
        String step = stepKey == null || stepKey.trim().isEmpty() ? null : stepKey.trim();
        if (ref == null || ref.trim().isEmpty()) {
            RunItems.ListRequest selection = selection(jobQueueId, refs, all, status, search, except);
            if (selection == null) {
                return answer(RunItems.Answer.refused(400, "Name the item (ref), the items (refs), or all of them (all)."));
            }
            return answer(this.items.zipLinkOfMany(selection, step));
        }
        return answer(this.items.zipLink(jobQueueId, ref, step));
    }

    /** A bulk selection from query parameters: the refs ticked, or with all every item the status and search find, less except. */
    static RunItems.ListRequest selection(Long jobQueueId, List<String> refs, boolean all, String status, String search, List<String> except) {
        if (!all && (refs == null || refs.isEmpty())) {
            return null;
        }
        RunItems.ListRequest selection = new RunItems.ListRequest();
        selection.jobQueueId = jobQueueId;
        selection.refs = all ? null : refs;
        selection.status = status;
        selection.search = search;
        selection.except = except;
        return selection;
    }

    @AuditedRead(value = "run item files (zip)", id = {"jobQueueId"}, target = false)
    @RequestMapping(value = "/sourceJob.json/runItems/zip", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> zip(@RequestParam Long jobQueueId, @RequestParam String token) throws JsonProcessingException {
        return stream(this.items.zip(jobQueueId, token), false);
    }

    @AuditedRead(value = "run items (CSV)", id = {"jobQueueId"})
    @RequestMapping(value = "/sourceJob.json/runItems/csv", method = RequestMethod.GET)
    public ResponseEntity<StreamingResponseBody> csv(@RequestParam Long jobQueueId, @RequestParam(required = false) String status,
        @RequestParam(required = false) String search, @RequestParam(required = false) List<String> fields,
        @RequestParam(required = false) List<String> refs, @RequestParam(required = false) List<String> except) throws JsonProcessingException {
        // MIG-421: a selection's CSV -- the refs ticked, or all matching less except.
        RunItems.ListRequest request = new RunItems.ListRequest();
        request.jobQueueId = jobQueueId;
        request.status = status;
        request.search = search;
        request.fields = fields == null ? Collections.<String>emptyList() : fields;
        request.refs = refs;
        request.except = except;
        return stream(this.items.csv(request), false);
    }

    /** MIG-421: what a bulk selection names -- how many items, how many failed, their refs -- before a bulk action. */
    @ReadOnlyList("a selection of a run's items")
    @RequestMapping(value = "/sourceJob.json/runItems/selection", method = RequestMethod.POST)
    public ResponseEntity<?> selectRunItems(@RequestBody(required = false) RunItems.ListRequest request) {
        return answer(this.items.selection(request == null ? new RunItems.ListRequest() : request));
    }

    /**
     * MIG-421: the selected items sent to review -- a Task inbox task for a team ({kind: team, value: its id}) or a role, naming
     * them and linking to the run's Items tab showing them. The run's owner or an administrator (the Source Jobs page).
     */
    @RequestMapping(value = "/sourceJob.json/reviewRunItems", method = RequestMethod.POST)
    public ResponseEntity<?> reviewRunItems(@RequestBody(required = false) RunItemReviews.Request request) {
        return answer(this.reviews.send(request == null ? new RunItemReviews.Request() : request, TenantContext.getAppUserId()));
    }

    /** MIG-421: a schedule's items across its runs, newest first (the Schedules page's Items tab), from the item records. */
    @ReadOnlyList("a schedule's items across its runs")
    @RequestMapping(value = "/sourceJob.json/itemRuns", method = RequestMethod.POST)
    public ResponseEntity<?> listItemRuns(@RequestBody(required = false) ItemsAcrossRuns.Request request) {
        return answer(this.across.list(request == null ? new ItemsAcrossRuns.Request() : request));
    }

    /** MIG-421: one item of a schedule in two runs, step by step (masked), and what changed in the pipeline between them. */
    @AuditedRead(value = "run item compare", id = {"jobId", "key", "a", "b"})
    @RequestMapping(value = "/sourceJob.json/itemRuns/compare", method = RequestMethod.GET)
    public ResponseEntity<?> compare(@RequestParam Long jobId, @RequestParam String key, @RequestParam Long a, @RequestParam Long b) {
        return answer(this.across.compare(jobId, key, a, b));
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
