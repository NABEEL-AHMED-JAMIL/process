package process.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-426: a run's result in the formats a webhook chose, for integration-service's delivery of run.completed -- service to
 * service, with the shared X-Internal-Token, as the event's workspace. POST /internal/customer/runDocuments {tenantId, runId,
 * formats: [json, pdf, html, md], report?, via?, who?, maxBytes?} answers {runId, status, results, problems}:
 *
 * <ul>
 *   <li>results.json: the result as GET /v1/runs/{id}/result gives it (masked by the workspace's data policy);</li>
 *   <li>results.md and results.html: {text, contentType, fileName, report, version, signed} -- the report's (or, with none,
 *   the result rows'); results.pdf: {base64, bytes, contentType, fileName, report, version, signed} -- the report's PDF, the
 *   signed one once signed;</li>
 *   <li>problems.&lt;format&gt;: {status, type, detail} for a format the run cannot be given in -- a sensitive pipeline's
 *   report (403 sensitive-files), no report for a PDF (404 no-report), several reports (409), or more than {@code maxBytes}
 *   (default 5 MB, the most a delivery carries) once the formats before it are counted (413 too-large).</li>
 * </ul>
 * The same rules as the customer API's (RunDocuments): a webhook's receiver is the API client's.
 */
@RestController
@RequestMapping("/internal/customer")
public class InternalRunDocumentsRestApi {

    private static final Logger logger = LoggerFactory.getLogger(InternalRunDocumentsRestApi.class);
    static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    static final long MOST_MAX_BYTES = 8L * 1024 * 1024;

    private final CustomerRuns runs;
    private final byte[] token;

    public InternalRunDocumentsRestApi(CustomerRuns runs, @Value("${internal.service-token:}") String token) {
        this.runs = runs;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** What a delivery asks for. */
    public static class DocumentsRequest {
        private Long tenantId;
        private Long runId;
        private List<String> formats;
        private String report;
        private String via;
        private String who;
        private Long maxBytes;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRunId() {
            return runId;
        }

        public void setRunId(Long runId) {
            this.runId = runId;
        }

        public List<String> getFormats() {
            return formats;
        }

        public void setFormats(List<String> formats) {
            this.formats = formats;
        }

        public String getReport() {
            return report;
        }

        public void setReport(String report) {
            this.report = report;
        }

        public String getVia() {
            return via;
        }

        public void setVia(String via) {
            this.via = via;
        }

        public String getWho() {
            return who;
        }

        public void setWho(String who) {
            this.who = who;
        }

        public Long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(Long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    @PostMapping(value = "/runDocuments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runDocuments(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) DocumentsRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() < 1 || request.getRunId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name the workspace (tenantId) and the run (runId)."));
        }
        List<ResultFormat> formats = new ArrayList<>();
        for (String word : request.getFormats() == null ? new ArrayList<String>() : request.getFormats()) {
            Optional<ResultFormat> f = ResultFormat.of(word);
            if (!f.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("message", ResultFormat.FORMAT_REFUSAL));
            }
            if (!formats.contains(f.get())) {
                formats.add(f.get());
            }
        }
        long budget = request.getMaxBytes() == null ? DEFAULT_MAX_BYTES : Math.max(1, Math.min(MOST_MAX_BYTES, request.getMaxBytes()));
        String via = request.getVia() == null || request.getVia().isBlank() ? "webhook" : request.getVia().trim();
        String who = request.getWho() == null || request.getWho().isBlank() ? via : request.getWho().trim();
        TenantContext.set(request.getTenantId(), "TENANT_ADMIN", null, "integration-service");
        try {
            Optional<Map<ResultFormat, Object>> found = this.runs.resultsFor(request.getTenantId(), request.getRunId(), formats,
                request.getReport(), who.length() <= 64 ? who : who.substring(0, 64), via);
            if (!found.isPresent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "No run " + request.getRunId() + " in this workspace."));
            }
            Map<String, Object> results = new LinkedHashMap<>();
            Map<String, Object> problems = new LinkedHashMap<>();
            long used = 0;
            for (Map.Entry<ResultFormat, Object> one : found.get().entrySet()) {
                ResultFormat format = one.getKey();
                Object value = one.getValue();
                if (value instanceof RunDocuments.Outcome && ((RunDocuments.Outcome) value).document == null) {
                    problems.put(format.word, problem(((RunDocuments.Outcome) value)));
                    continue;
                }
                if (!format.document()) {
                    results.put(format.word, value);
                    continue;
                }
                ResultDocument d = ((RunDocuments.Outcome) value).document;
                long size = format == ResultFormat.PDF ? (d.bytes.length + 2L) / 3 * 4 : d.bytes.length;
                if (used + size > budget) {
                    Map<String, Object> tooLarge = new LinkedHashMap<>();
                    tooLarge.put("status", 413);
                    tooLarge.put("type", "/problems/too-large");
                    tooLarge.put("detail", String.format("The %s is %,d bytes, more than this delivery carries (%,d with the formats before it); read "
                        + "it with GET /v1/runs/%d/result?format=%s.", format.word, size, budget, request.getRunId(), format.word));
                    problems.put(format.word, tooLarge);
                    continue;
                }
                used += size;
                Map<String, Object> doc = new LinkedHashMap<>();
                if (format == ResultFormat.PDF) {
                    doc.put("base64", Base64.getEncoder().encodeToString(d.bytes));
                    doc.put("bytes", d.bytes.length);
                } else {
                    doc.put("text", new String(d.bytes, StandardCharsets.UTF_8));
                }
                doc.put("contentType", d.contentType);
                doc.put("fileName", d.fileName);
                doc.put("report", d.reportLabel);
                doc.put("version", d.reportVersion);
                doc.put("signed", d.signed);
                results.put(format.word, doc);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", String.valueOf(request.getRunId()));
            body.put("results", results);
            body.put("problems", problems);
            return ResponseEntity.ok(body);
        } catch (RuntimeException failed) {
            logger.warn("Run {} of workspace {}: its documents could not be made: {}", request.getRunId(), request.getTenantId(), failed.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "The run's result cannot be read right now."));
        } finally {
            TenantContext.clear();
        }
    }

    private static Map<String, Object> problem(RunDocuments.Outcome outcome) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", outcome.problem.getStatus());
        p.put("type", outcome.problem.getType());
        p.put("detail", outcome.problem.getDetail());
        if (outcome.reports != null) {
            p.put("reports", outcome.reports);
        }
        return p;
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            logger.warn("Refused a run documents request without the internal token.");
        }
        return ok;
    }
}
