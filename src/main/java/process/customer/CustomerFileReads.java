package process.customer;

import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.pipeline.DatasetStore;
import process.pipeline.FileAccessLog;
import process.pipeline.RunOutput;
import process.pipeline.StepStore;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.security.TenantContext;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reading a file by its id through the customer API (MIG-334, ADR-025 decision 3; OpenAPI getFile, downloadFile,
 * readFileContent). A file id names either a file a run made (run_output, Core's: a kept file, a report, an object a
 * step uploaded to a workspace bucket) or a file the workspace uploaded (stored_file, storage-service's, through its
 * directory). Reading by id is Core's because a run's made files live with Core; uploading stays storage-service's.
 *
 * <ul>
 *   <li>GET /v1/files/{id}/meta: the File; scope files:read.</li>
 *   <li>GET /v1/files/{id}: 302 to the file's signed link (FileLinks), valid 5 minutes, with {url, expiresAt}; scope
 *   files:read. A made file past its expiry is 410.</li>
 *   <li>GET /v1/files/{id}/content?token=: the bytes, for whoever holds the link; no access token. Read in the link's
 *   workspace (RowSecurity.forTenant: the request has no caller), never another's, and recorded in file_access_log
 *   with the client that asked for the link.</li>
 * </ul>
 * Another workspace's file is a 404, the same answer as one that does not exist; nothing ever names a bucket, a storage
 * key or a connection.
 */
@Service
public class CustomerFileReads {

    private static final Logger logger = LoggerFactory.getLogger(CustomerFileReads.class);
    static final Pattern FILE_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    static final String NO_SUCH_FILE = "No such file.";
    static final String EXPIRED_FILE = "This file was kept for its pipeline's retention and has expired; run the pipeline again to make it anew.";
    static final String EXPIRED_LINK = "This link has expired. Ask GET /v1/files/{fileId} for a new one.";

    /** A file of the workspace, by id. */
    static final class Resolved {
        /** A run's made file, or null for an upload. */
        StepStore.OutputRow output;
        /** An upload as storage-service's directory answers it, or null for a made file. */
        Map<String, Object> upload;
        Map<String, Object> view;
        String name;
        String contentType;
        Long runId;
        boolean expired;
    }

    /** What the content endpoint answers: the bytes, or a problem. */
    public static final class Content {
        public final int status;
        public final Map<String, Object> problem;
        public final String name;
        public final String contentType;
        public final long size;
        public final InputStream body;
        /** The run that made it, for the access log; null for an upload or a refusal. */
        final Long runId;

        Content(int status, Map<String, Object> problem, String name, String contentType, long size, InputStream body, Long runId) {
            this.status = status;
            this.problem = problem;
            this.name = name;
            this.contentType = contentType;
            this.size = size;
            this.body = body;
            this.runId = runId;
        }

        static Content refused(Problem problem, String instance) {
            return new Content(problem.getStatus(), problem.at(instance).toMap(), null, null, -1, null, null);
        }
    }

    /** storage-service could not say whether an uploaded file is the workspace's. */
    public static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final StepStore steps;
    private final CustomerRunStore runs;
    private final RunFiles files;
    private final DatasetStore datasets;
    private final BucketStore buckets;
    private final FileLinks links;
    private final FileAccessLog accessLog;

    public CustomerFileReads(StepStore steps, CustomerRunStore runs, RunFiles files, DatasetStore datasets, BucketStore buckets, FileLinks links,
        FileAccessLog accessLog) {
        this.steps = steps;
        this.runs = runs;
        this.files = files;
        this.datasets = datasets;
        this.buckets = buckets;
        this.links = links;
        this.accessLog = accessLog;
    }

    public CustomerAnswer meta(String fileId) {
        String instance = "/v1/files/" + fileId + "/meta";
        if (!TenantContext.hasScope(ApiScopes.FILES_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.FILES_READ), instance);
        }
        Optional<Resolved> found;
        try {
            found = this.resolve(TenantContext.getTenantId(), fileId);
        } catch (Unavailable unavailable) {
            return CustomerAnswer.problem(unavailable(), instance);
        }
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_FILE), instance);
        }
        return CustomerAnswer.of(200, found.get().view, null);
    }

    /** 302 to the signed link, with {url, expiresAt} for a caller that does not follow redirects. */
    public CustomerAnswer link(String fileId) {
        String instance = "/v1/files/" + fileId;
        if (!TenantContext.hasScope(ApiScopes.FILES_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.FILES_READ), instance);
        }
        long tenantId = TenantContext.getTenantId();
        Optional<Resolved> found;
        try {
            found = this.resolve(tenantId, fileId);
        } catch (Unavailable unavailable) {
            return CustomerAnswer.problem(unavailable(), instance);
        }
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_FILE), instance);
        }
        if (found.get().expired) {
            return CustomerAnswer.problem(Problem.of(410, EXPIRED_FILE).kind("file-expired"), instance);
        }
        if (!this.links.available()) {
            logger.warn("A file link was asked for but cannot be signed: internal.service-token is not set.");
            return CustomerAnswer.problem(unavailable(), instance);
        }
        FileLinks.Issued issued = this.links.issue(tenantId, fileId, TenantContext.getClientId());
        String url = "/v1/files/" + fileId + "/content?token=" + issued.token;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("expiresAt", ApiTimes.utc(issued.expiresAt));
        return CustomerAnswer.of(302, body, url);
    }

    /** The bytes a signed link opens; the link is the only permission, and its workspace the only one read. */
    public Content content(String fileId, String token) {
        String instance = "/v1/files/" + fileId + "/content";
        FileLinks.Checked checked = this.links.check(fileId, token);
        if (checked.verdict == FileLinks.Verdict.NOT_OURS) {
            return Content.refused(Problem.of(404, NO_SUCH_FILE), instance);
        }
        FileLinks.Link link = checked.link;
        if (checked.verdict == FileLinks.Verdict.EXPIRED) {
            this.accessLog.apiFile(link.tenantId, link.clientId, fileId, null, null, false, 410, "link expired");
            return Content.refused(Problem.of(410, EXPIRED_LINK).kind("link-expired"), instance);
        }
        Content content = RowSecurity.forTenant(link.tenantId, () -> this.open(link, instance));
        this.accessLog.apiFile(link.tenantId, link.clientId, fileId, content.runId, content.name, content.status == 200, content.status,
            content.problem == null ? null : String.valueOf(content.problem.get("detail")));
        return content;
    }

    private Content open(FileLinks.Link link, String instance) {
        Optional<Resolved> found;
        try {
            found = this.resolve(link.tenantId, link.fileId);
        } catch (Unavailable unavailable) {
            return Content.refused(unavailable(), instance);
        }
        if (!found.isPresent()) {
            return Content.refused(Problem.of(404, NO_SUCH_FILE), instance);
        }
        Resolved file = found.get();
        if (file.expired) {
            return Content.refused(Problem.of(410, EXPIRED_FILE).kind("file-expired"), instance);
        }
        try {
            if (file.output != null && RunOutput.FILE.equals(file.output.kind)) {
                Optional<StepStore.DatasetFile> kept = file.output.runDatasetId == null ? Optional.empty()
                    : this.steps.datasetById(file.output.runDatasetId);
                if (!kept.isPresent()) {
                    return Content.refused(Problem.of(410, EXPIRED_FILE).kind("file-expired"), instance);
                }
                // MIG-344: streamed from the store, never held whole (a streamed Save File can be gigabytes).
                InputStream content;
                long size;
                try {
                    size = this.datasets.fileSize(kept.get().storageKey);
                    content = this.datasets.openFile(kept.get().storageKey);
                } catch (Exception gone) {
                    logger.warn("Made file {} of run {} could not be read from the datasets: {}", link.fileId, file.runId, gone.getMessage());
                    return Content.refused(Problem.of(410, "This file's content is no longer available; run the pipeline again to make it "
                        + "anew.").kind("file-expired"), instance);
                }
                return new Content(200, null, file.name, file.contentType, size, content, file.runId);
            }
            String bucket = file.output != null ? file.output.bucketAlias : (String) file.upload.get("bucket");
            String key = file.output != null ? file.output.objectKey : (String) file.upload.get("key");
            BucketStore.Streamed streamed = this.buckets.stream(link.tenantId, bucket, key);
            return new Content(200, null, file.name, file.contentType != null ? file.contentType : streamed.contentType, streamed.size,
                streamed.content, file.runId);
        } catch (Exception unreadable) {
            logger.warn("File {} of workspace {} could not be read: {}", link.fileId, link.tenantId, unreadable.getMessage());
            return Content.refused(unavailable(), instance);
        }
    }

    /**
     * The workspace's file by id: a run's made file (its run the workspace's and not deleted), else an upload of the
     * workspace's (storage-service's directory); empty for anything else.
     */
    Optional<Resolved> resolve(long tenantId, String fileId) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            return Optional.empty();
        }
        Optional<StepStore.OutputRow> made = this.steps.outputByFileId(fileId);
        if (made.isPresent()) {
            if (!this.runs.find(tenantId, made.get().jobQueueId).isPresent()) {
                return Optional.empty();
            }
            Resolved file = new Resolved();
            file.output = made.get();
            file.view = CustomerViews.madeFile(made.get(), fileId, Instant.now());
            file.name = made.get().name;
            file.contentType = FileFormats.contentType(made.get().format);
            file.runId = made.get().jobQueueId;
            file.expired = CustomerViews.expired(made.get(), Instant.now());
            return Optional.of(file);
        }
        Map<String, Map<String, Object>> uploaded;
        try {
            uploaded = this.files.of(tenantId, Collections.singletonList(fileId));
        } catch (RuntimeException unreachable) {
            logger.warn("File {} could not be looked up in storage-service's directory: {}", fileId, unreachable.getMessage());
            throw new Unavailable(unreachable.getMessage(), unreachable);
        }
        Map<String, Object> upload = uploaded.get(fileId);
        if (upload == null) {
            return Optional.empty();
        }
        Resolved file = new Resolved();
        file.upload = upload;
        file.view = CustomerViews.uploadedFile(upload);
        file.view.remove("step");
        file.view.remove("rows");
        file.view.remove("expired");
        file.name = (String) upload.get("name");
        file.contentType = (String) upload.get("contentType");
        return Optional.of(file);
    }

    static Problem unavailable() {
        return Problem.of(503, "The file cannot be read right now. Try again in a moment.");
    }
}
