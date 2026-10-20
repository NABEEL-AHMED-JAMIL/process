package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import process.pipeline.data.Values;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The other services a pipeline's tasks call, in memory: each remembers what it was asked and answers as told. */
public final class Fakes {

    private Fakes() {
    }

    /** integration-service's runner: answers each call with {@code answer(variables)}, a JSON text. */
    public static final class Api implements ApiRunner {
        public final List<ApiCall> calls = new ArrayList<>();
        public Function<Map<String, String>, String> answer = variables -> "{}";
        public String outcome = "OK";
        public String unavailable;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public synchronized ApiRunResult run(ApiCall call) throws Exception {
            this.calls.add(call);
            ApiRunResult result = new ApiRunResult();
            result.outcome = this.outcome;
            result.statusCode = "OK".equals(this.outcome) ? 200 : 500;
            result.message = "OK".equals(this.outcome) ? null : "upstream said no";
            result.body = Values.JSON.readTree(this.answer.apply(call.variables));
            return result;
        }
    }

    /** A contract that holds when {@code holds} says so of a row. */
    public static final class Contracts implements ContractChecker {
        public final List<ContractCall> calls = new ArrayList<>();
        public Predicate<Map<String, Object>> holds = row -> true;
        public String unavailable;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public ContractVerdicts validate(ContractCall call) {
            this.calls.add(call);
            ContractVerdicts verdicts = new ContractVerdicts();
            verdicts.contractId = call.contractId;
            verdicts.name = "claims";
            verdicts.version = 3;
            verdicts.rows = new ArrayList<>();
            for (int i = 0; i < call.rows.size(); i++) {
                boolean valid = this.holds.test(call.rows.get(i));
                verdicts.rows.add(new RowVerdict(i, valid, valid ? Collections.<String>emptyList()
                    : Collections.singletonList("/amount: must be a number")));
            }
            return verdicts;
        }
    }

    public static class Database implements DatabaseReader, DatabaseWriter {
        public final List<QueryCall> queries = new ArrayList<>();
        public final List<WriteCall> writes = new ArrayList<>();
        public List<Map<String, Object>> rows = new ArrayList<>();
        public String unavailable;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public QueryResult query(QueryCall call) {
            this.queries.add(call);
            QueryResult result = new QueryResult();
            result.rows = new ArrayList<>(this.rows.subList(0, Math.min(this.rows.size(), call.maxRows)));
            result.columns = this.rows.isEmpty() ? new ArrayList<>() : new ArrayList<>(this.rows.get(0).keySet());
            result.truncated = this.rows.size() > call.maxRows;
            return result;
        }

        @Override
        public long write(WriteCall call) {
            this.writes.add(call);
            return call.rows.size();
        }
    }

    public static final class Buckets implements BucketStore {
        public final Map<String, byte[]> objects = new LinkedHashMap<>();
        public final List<String> uploads = new ArrayList<>();
        public String unavailable;
        public long lastTenant;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            this.lastTenant = tenantId;
            List<Listed> listed = new ArrayList<>();
            for (Map.Entry<String, byte[]> object : this.objects.entrySet()) {
                if (object.getKey().startsWith(bucket + "/" + prefix)) {
                    listed.add(new Listed(object.getKey().substring(bucket.length() + 1), object.getValue().length));
                }
            }
            boolean truncated = listed.size() > limit;
            return new Listing(truncated ? listed.subList(0, limit) : listed, truncated);
        }

        @Override
        public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            this.lastTenant = tenantId;
            byte[] content = this.objects.get(bucket + "/" + key);
            if (content == null) {
                throw new IllegalStateException("No object at " + bucket + "/" + key + ".");
            }
            return content;
        }

        @Override
        public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.lastTenant = tenantId;
            this.objects.put(bucket + "/" + key, content);
            this.uploads.add(bucket + "/" + key + " " + contentType);
        }
    }

    /** A bucket store that keeps each workspace's objects apart: tenant -> "bucket/key" -> bytes. */
    public static final class TenantBuckets implements BucketStore {
        public final Map<Long, Map<String, byte[]>> objects = new LinkedHashMap<>();
        public final List<String> reads = new ArrayList<>();
        public final List<String> uploads = new ArrayList<>();

        public TenantBuckets put(long tenantId, String bucketAndKey, byte[] content) {
            this.objects.computeIfAbsent(tenantId, t -> new LinkedHashMap<>()).put(bucketAndKey, content);
            return this;
        }

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            this.reads.add(tenantId + ":" + bucket + "/" + key);
            byte[] content = this.objects.getOrDefault(tenantId, Collections.emptyMap()).get(bucket + "/" + key);
            if (content == null) {
                throw new IllegalStateException("No object at " + bucket + "/" + key + " in this workspace.");
            }
            if (content.length > maxBytes) {
                throw new IllegalStateException(String.format("%s/%s is %,d bytes; a step reads at most %,d.", bucket, key, content.length, maxBytes));
            }
            return content;
        }

        @Override
        public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.objects.computeIfAbsent(tenantId, t -> new LinkedHashMap<>()).put(bucket + "/" + key, content);
            this.uploads.add(tenantId + ":" + bucket + "/" + key + " " + contentType);
        }
    }

    /** media-service's pipeline files: remembers each call; answers as told, busy {@code busyFor} times first. */
    public static final class Media implements MediaFiles {
        public final List<String> calls = new ArrayList<>();
        public final List<byte[]> sent = new ArrayList<>();
        public String unavailable;
        public int busyFor;
        /** fileName -> a refusal's status and sentence for that file. */
        public final Map<String, MediaFiles.Refused> refusals = new LinkedHashMap<>();
        public double seconds = 75.0;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        private void asked(String what, long tenantId, String usageKey, Path file, String fileName) throws IOException {
            this.calls.add(what + " " + tenantId + " " + usageKey + " " + fileName);
            this.sent.add(Files.readAllBytes(file));
            if (this.busyFor > 0) {
                this.busyFor--;
                throw new MediaFiles.Refused(503, "The transcriber is busy.", 7);
            }
            MediaFiles.Refused refused = this.refusals.get(fileName);
            if (refused != null) {
                throw refused;
            }
        }

        @Override
        public Transcript transcribe(long tenantId, String usageKey, Path file, String fileName, boolean timestamps)
            throws IOException {
            this.asked("transcribe", tenantId, usageKey, file, fileName);
            Transcript t = new Transcript();
            t.text = "text of " + fileName;
            t.durationSeconds = this.seconds;
            t.language = "en";
            t.model = "whisper-small";
            if (timestamps) {
                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("startMs", 0L);
                segment.put("endMs", 1500L);
                segment.put("text", "text of " + fileName);
                t.segments = new ArrayList<>(Collections.singletonList(segment));
            }
            return t;
        }

        @Override
        public Converted convert(long tenantId, String usageKey, Path file, String fileName, String outputFormat,
            Path out) throws IOException {
            this.asked("convert", tenantId, usageKey, file, fileName);
            byte[] bytes = ("%PDF-converted " + fileName).getBytes(StandardCharsets.UTF_8);
            Files.write(out, bytes);
            String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            return new Converted(stem + "." + outputFormat, "pdf".equals(outputFormat) ? 3 : null, bytes.length);
        }
    }

    public static final class Notifier implements PipelineNotifier {
        public final List<Notice> sent = new ArrayList<>();

        @Override
        public int send(Notice notice) {
            this.sent.add(notice);
            return 1;
        }
    }

    /** MIG-346: integration-service's webhooks, recording each send; a delivery is Delivered unless told otherwise. */
    public static final class Webhooks implements WebhookSender {
        public final List<Send> sent = new ArrayList<>();
        public final Map<String, Long> deliveries = new LinkedHashMap<>();
        public String deliveryStatus = "Delivered";
        public String refusal;

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public Sent send(Send send) {
            if (this.refusal != null) {
                throw new IllegalArgumentException(this.refusal);
            }
            Sent answer = new Sent();
            answer.eventId = send.eventId;
            answer.replay = this.deliveries.containsKey(send.eventId);
            if (!answer.replay) {
                this.sent.add(send);
                this.deliveries.put(send.eventId, 5000L + this.deliveries.size());
            }
            answer.deliveryId = this.deliveries.get(send.eventId);
            answer.status = "Pending";
            return answer;
        }

        @Override
        public Delivery delivery(long tenantId, long webhookId, long deliveryId) {
            Delivery delivery = new Delivery();
            delivery.status = this.deliveryStatus;
            delivery.attempts = "Pending".equals(this.deliveryStatus) ? 0 : 1;
            delivery.responseStatus = "Delivered".equals(this.deliveryStatus) ? 200 : "Pending".equals(this.deliveryStatus) ? null : 500;
            delivery.lastError = "Delivered".equals(this.deliveryStatus) || "Pending".equals(this.deliveryStatus) ? null : "HTTP 500";
            return delivery;
        }
    }

    /** A JSON text as a tree, for a fake's answer. */
    public static JsonNode json(String text) {
        try {
            return Values.JSON.readTree(text);
        } catch (Exception broken) {
            throw new IllegalArgumentException(broken);
        }
    }

    /**
     * MIG-364: Document Intelligence's documents (ai-service's trusted read) and the read_documents watermark. Each document
     * belongs to a workspace and a type; a read sees only its own workspace's (as ai-service's row security does), a page
     * at a time in the cursor's order. Every request is kept, to see how a step read.
     */
    public static final class Documents implements DocumentDatasets, DocumentReadMarks {
        public static final ObjectMapper JSON = new ObjectMapper();

        public final List<String> fields = new ArrayList<>(Arrays.asList("invoice_number", "vendor_id", "total"));
        public final List<String> tables = new ArrayList<>(Collections.singletonList("line_items"));
        public final Map<String, List<String>> tableColumns = new LinkedHashMap<>();
        /** {tenant, type, status, row}: every document, in dataset order. */
        public final List<Object[]> documents = new ArrayList<>();
        public final List<Request> requests = new ArrayList<>();
        public String refusal;
        /** run_document_read: job#step#type -> {jobQueueId, upTo, rows}; and the runs that completed. */
        public final Map<String, List<long[]>> marks = new LinkedHashMap<>();
        public final Set<Long> completed = new HashSet<>();
        private long nextRow = 1000;
        private long nextExtraction = 5000;

        public Documents() {
            this.tableColumns.put("line_items", Arrays.asList("sku", "quantity", "amount"));
        }

        /** An approved document of the type in the workspace: its dataset row id. */
        public long approve(long tenant, long type, String invoice, String vendor, double total, int lines) {
            ObjectNode row = JSON.createObjectNode();
            long rowId = this.nextRow++;
            long extraction = this.nextExtraction++;
            row.put("datasetRowId", rowId);
            row.put("extractionId", extraction);
            row.put("ocrDocumentId", extraction + 100);
            row.put("fileName", invoice + ".pdf");
            row.put("typeVersion", 1);
            row.put("status", APPROVED);
            row.put("approval", rowId % 2 == 0 ? "auto" : "reviewer");
            row.putNull("approvedBy");
            row.put("dateApproved", "2026-10-12 09:00:00");
            row.put("dateDecided", "2026-10-12 09:00:00");
            row.putArray("rulesFailed");
            row.putNull("rejectReason");
            row.putObject("fields").put("invoice_number", invoice).put("vendor_id", vendor).put("total", total);
            ArrayNode items = row.putObject("tables").putArray("line_items");
            for (int i = 0; i < lines; i++) {
                items.addObject().put("sku", "SKU-" + i).put("quantity", i + 1).put("amount", 10.0 * (i + 1));
            }
            this.documents.add(new Object[] {tenant, type, APPROVED, row});
            return rowId;
        }

        public long extractionOf(long rowId) {
            for (Object[] d : this.documents) {
                JsonNode row = (JsonNode) d[3];
                if (row.path("datasetRowId").asLong() == rowId) {
                    return row.path("extractionId").asLong();
                }
            }
            throw new IllegalArgumentException("no row " + rowId);
        }

        @Override
        public Page read(Request r) {
            this.requests.add(copy(r));
            if (this.refusal != null) {
                throw new IllegalArgumentException(this.refusal);
            }
            Page page = new Page();
            page.documentTypeId = r.documentTypeId;
            page.typeKey = "invoice";
            page.name = "AP invoice";
            page.fields.addAll(this.fields);
            page.tables.addAll(this.tables);
            page.tableColumns.putAll(this.tableColumns);
            String status = r.status == null ? APPROVED : r.status;
            for (Object[] d : this.documents) {
                JsonNode row = (JsonNode) d[3];
                long cursor = APPROVED.equals(status) ? row.path("datasetRowId").asLong() : row.path("extractionId").asLong();
                if ((Long) d[0] != r.tenantId || (Long) d[1] != r.documentTypeId || !status.equals(d[2]) || cursor <= r.after) {
                    continue;
                }
                if (r.extractionIds != null && !r.extractionIds.contains(row.path("extractionId").asLong())) {
                    continue;
                }
                if (page.rows.size() == r.limit) {
                    break;
                }
                page.rows.add(row);
                page.next = cursor;
            }
            if (page.rows.size() < r.limit) {
                page.next = null;
            }
            return page;
        }

        private static Request copy(Request r) {
            Request c = new Request();
            c.tenantId = r.tenantId;
            c.documentTypeId = r.documentTypeId;
            c.status = r.status;
            c.after = r.after;
            c.from = r.from;
            c.to = r.to;
            c.extractionIds = r.extractionIds;
            c.limit = r.limit;
            c.names = r.names;
            c.jobQueueId = r.jobQueueId;
            c.readKey = r.readKey;
            return c;
        }

        @Override
        public long watermark(long jobId, String stepKey, long documentTypeId) {
            long mark = 0;
            for (long[] m : this.marks.getOrDefault(jobId + "#" + stepKey + "#" + documentTypeId, Collections.emptyList())) {
                if (this.completed.contains(m[0])) {
                    mark = Math.max(mark, m[1]);
                }
            }
            return mark;
        }

        @Override
        public void record(long jobQueueId, String stepKey, long jobId, long documentTypeId, long upTo, long rows, int attempt) {
            List<long[]> list = this.marks.computeIfAbsent(jobId + "#" + stepKey + "#" + documentTypeId, k -> new ArrayList<>());
            list.removeIf(m -> m[0] == jobQueueId);
            list.add(new long[] {jobQueueId, upTo, rows});
        }
    }

    /** read_runs' history: the rows it answers, and what it was last asked. */
    public static final class Runs implements RunHistory {
        public final List<Map<String, Object>> rows = new ArrayList<>();
        public String unavailable;
        public long lastTenant;
        public String lastPipeline;
        public Instant lastSince;
        public boolean lastFinishedOnly;
        public int lastLimit;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public List<Map<String, Object>> runs(long tenantId, String pipelineId, Instant since, boolean finishedOnly, int limit) {
            this.lastTenant = tenantId;
            this.lastPipeline = pipelineId;
            this.lastSince = since;
            this.lastFinishedOnly = finishedOnly;
            this.lastLimit = limit;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : this.rows) {
                if (pipelineId.equals(row.get("pipeline_id")) && out.size() < limit) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
