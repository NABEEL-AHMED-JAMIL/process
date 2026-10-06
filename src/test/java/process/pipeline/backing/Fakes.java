package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import process.pipeline.data.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

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

    public static final class Notifier implements PipelineNotifier {
        public final List<Notice> sent = new ArrayList<>();

        @Override
        public int send(Notice notice) {
            this.sent.add(notice);
            return 1;
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
}
