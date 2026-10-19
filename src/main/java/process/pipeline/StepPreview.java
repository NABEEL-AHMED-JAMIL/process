package process.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.model.dto.ResponseDto;
import process.pipeline.backing.ApiRunner;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.data.Limits;
import process.pipeline.registry.ConfigSchemaValidator;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.tasks.EnrichStepTask;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * AI tools batch (2026-10-13, owner item #14): POST pipeline.json/steps/preview -- one step of the Task Registry run on a
 * few rows, outside any run, as the signed-in person, so the AI Assistant (preview_step, transform_data, enrich_data,
 * join_data) and later the step builder can see what a step makes before it is in a pipeline.
 *
 * Sandboxed:
 * <ul>
 *   <li>only tasks that turn rows into rows ({@link #PREVIEWABLE}) -- nothing that writes, sends, uploads, saves, asks for an
 *       approval or calls a model; the task must be on for the workspace and within the person's role, as a pipeline's;</li>
 *   <li>at most {@value #MAX_ROWS} rows in and {@value #MAX_INPUTS} other inputs, at most {@value #MAX_BYTES} bytes of rows
 *       in all, the step's own row and cell bounds, and {@value #TIMEOUT_SECONDS} seconds; the answer is the output's
 *       columns and at most {@value #SHOWN} rows;</li>
 *   <li>no external call except a read the person's workspace may do: Enrich only with a saved request of the workspace's
 *       that reads (GET or HEAD), at most {@value #MAX_CALLS} calls; Validate reads its contract. Nothing is kept: no run,
 *       no dataset, no file, no output;</li>
 *   <li>the rows shown are masked where a column's name says it holds a person's details (name, birth date, address,
 *       phone, email, record numbers...) or a value looks like one (an email, a phone or card number).</li>
 * </ul>
 */
@Component
public class StepPreview {

    static final int MAX_ROWS = 50;
    static final int MAX_INPUTS = 4;
    static final int MAX_BYTES = 1_000_000;
    static final int SHOWN = 20;
    static final int MAX_CALLS = 10;
    static final int MAX_OUT_ROWS = 10_000;
    static final int TIMEOUT_SECONDS = 20;

    /** The tasks a preview may run: rows in, rows out, nothing written, sent or asked of a model. */
    static final Set<String> PREVIEWABLE = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("transform", "filter", "select", "sort",
        "limit", "compute", "aggregate", "append", "join", "enrich", "validate", "sample")));

    /** One preview's request. */
    public static class PreviewRequest {
        public String task;
        public Map<String, Object> config;
        public List<Map<String, Object>> rows;
        /** Other steps' rows a step reads by key (a join's with, an append's steps). */
        public Map<String, List<Map<String, Object>>> inputs;
    }

    private final TaskRegistry registry;

    private final ExecutorService runner = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "step-preview");
        t.setDaemon(true);
        return t;
    });

    private ApiRunner api;

    private IntegrationNames names;

    public StepPreview(TaskRegistry registry) {
        this.registry = registry;
    }

    @Autowired(required = false)
    public void useIntegration(ApiRunner api, IntegrationNames names) {
        this.api = api;
        this.names = names;
    }

    public ResponseDto preview(PreviewRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            return refused("A step preview runs in a workspace, and this sign-in names none.");
        }
        if (request == null || request.task == null || request.task.trim().isEmpty()) {
            return refused("Name the task to preview (a task code from the Task Registry).");
        }
        String code = request.task.trim();
        Optional<StepTask> found = this.registry.tasks().find(code);
        if (!found.isPresent()) {
            return refused(String.format("There is no task '%s' in the Task Registry.", code));
        }
        if (!PREVIEWABLE.contains(code)) {
            return refused(String.format("A '%s' step cannot be previewed: a preview runs only steps that turn rows into rows (%s), never one "
                + "that writes, sends, saves or calls a model.", code, String.join(", ", PREVIEWABLE)));
        }
        StepTask task = found.get();
        Optional<String> refusal = this.registry.refusal(task, this.registry.overridesOf(tenantId), TenantContext.getUserRole());
        if (refusal.isPresent()) {
            return refused(refusal.get());
        }
        List<Map<String, Object>> rows = request.rows == null ? new ArrayList<>() : request.rows;
        Map<String, List<Map<String, Object>>> inputs = request.inputs == null ? new LinkedHashMap<>() : request.inputs;
        if (rows.size() > MAX_ROWS) {
            return refused(String.format("A preview takes at most %d rows; %d were given.", MAX_ROWS, rows.size()));
        }
        if (inputs.size() > MAX_INPUTS) {
            return refused(String.format("A preview takes at most %d other inputs.", MAX_INPUTS));
        }
        for (Map.Entry<String, List<Map<String, Object>>> in : inputs.entrySet()) {
            if (in.getValue() != null && in.getValue().size() > MAX_ROWS) {
                return refused(String.format("A preview takes at most %d rows per input; '%s' has %d.", MAX_ROWS, in.getKey(), in.getValue().size()));
            }
        }
        if (bytes(rows, inputs) > MAX_BYTES) {
            return refused(String.format("The rows given are larger than a preview takes (%,d bytes): send fewer, or narrower, rows.", MAX_BYTES));
        }
        Map<String, Object> config = request.config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.config);
        if ("enrich".equals(code)) {
            Object asked = config.get("maxCalls");
            int calls = asked instanceof Number ? ((Number) asked).intValue() : MAX_CALLS;
            config.put("maxCalls", Math.max(1, Math.min(calls, MAX_CALLS)));
        }
        Set<String> earlier = new HashSet<>(inputs.keySet());
        List<DefinitionProblem> problems = ConfigSchemaValidator.problems(task.spec().configSchema(), config, earlier);
        if (problems.isEmpty()) {
            problems = task.check(config);
        }
        if (!problems.isEmpty()) {
            List<Map<String, Object>> said = new ArrayList<>();
            for (DefinitionProblem p : problems) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", "config" + ("$".equals(p.getPath()) ? "" : "." + p.getPath()));
                one.put("message", p.getMessage());
                said.add(one);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("problems", said);
            return new ResponseDto(ProcessUtil.ERROR, "The step's settings are not valid: " + problems.get(0).getPath() + " " + problems.get(0).getMessage()
                + ".", data);
        }
        StepTask runnable = task;
        if ("enrich".equals(code)) {
            if (this.api == null || this.names == null) {
                return refused("Enrich cannot be previewed here: integration-service's runner is not wired to this Core.");
            }
            String why = this.readOnlyRequest(tenantId, config.get("requestId"));
            if (why != null) {
                return refused(why);
            }
            runnable = new EnrichStepTask(this.readsOnly(tenantId));
        }
        Optional<String> unavailable = runnable.unavailable();
        if (unavailable.isPresent()) {
            return refused(String.format("'%s' is not available here: %s.", code, unavailable.get()));
        }
        Dataset input = Dataset.of(rows);
        if (input.getColumns().size() > Limits.MAX_COLUMNS) {
            return refused(String.format("The rows have %d columns; a step holds at most %d.", input.getColumns().size(), Limits.MAX_COLUMNS));
        }
        PreviewContext context = new PreviewContext(tenantId, config, input, inputs);
        StepTask run = runnable;
        Future<StepResult> pending = this.runner.submit(() -> run.run(context));
        StepResult result;
        try {
            result = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            pending.cancel(true);
            return refused(String.format("The preview took longer than %d seconds and was stopped.", TIMEOUT_SECONDS));
        } catch (InterruptedException stopped) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return refused("The preview was stopped.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("log", context.lines);
            return new ResponseDto(ProcessUtil.ERROR, "The step failed on these rows: " + clip(message, 500), data);
        }
        Dataset out = result.getOutput() == null ? Dataset.of(rows) : result.getOutput();
        if (out.size() > MAX_OUT_ROWS) {
            return refused(String.format("The step made %,d rows from %d; a preview answers at most %,d.", out.size(), rows.size(), MAX_OUT_ROWS));
        }
        Set<String> masked = new LinkedHashSet<>();
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> row : out.getRows().subList(0, Math.min(SHOWN, out.size()))) {
            shown.add(mask(row, masked));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", code);
        data.put("rowsIn", rows.size());
        data.put("rowCount", out.size());
        data.put("columns", out.getColumns());
        data.put("shown", shown.size());
        data.put("rows", shown);
        data.put("masked", new ArrayList<>(masked));
        data.put("log", context.lines);
        data.put("warnings", context.warnings);
        if (result.getOutput() == null) {
            data.put("note", "The step acted and made no new rows; its input is shown.");
        }
        return new ResponseDto(ProcessUtil.SUCCESS, String.format("%s on %d row(s): %d row(s) out.", code, rows.size(), out.size()), data);
    }

    /** Null when the request is the workspace's and only reads; otherwise why it cannot be used in a preview. */
    String readOnlyRequest(long tenantId, Object requestId) {
        Long id = requestId instanceof Number ? ((Number) requestId).longValue() : requestId == null ? null : parse(requestId.toString());
        if (id == null) {
            return "Name the saved API request (requestId) to enrich with.";
        }
        IntegrationNames.Names described;
        try {
            described = this.names.describe(tenantId, Collections.singletonList(id), Collections.<Long>emptyList());
        } catch (Exception ex) {
            return "integration-service could not say what API request " + id + " is just now; try again.";
        }
        IntegrationNames.RequestName name = described.requests.get(id);
        if (name == null) {
            return String.format("There is no API request %d in this workspace.", id);
        }
        if (name.method == null) {
            // An integration-service that does not say the method: never assume a request only reads.
            return String.format("integration-service did not say whether API request %d only reads, so it is not previewed.", id);
        }
        String method = name.method.toUpperCase(Locale.ROOT);
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return String.format("API request %d (\"%s\") changes data (%s): a preview only reads.", id, name.name, method);
        }
        return null;
    }

    /** The runner, held to reads of the workspace's own requests (checked once more per call) and to the preview's call cap. */
    private ApiRunner readsOnly(long tenantId) {
        ApiRunner real = this.api;
        int[] made = {0};
        return new ApiRunner() {
            @Override
            public Optional<String> unavailable() {
                return real.unavailable();
            }

            @Override
            public ApiRunResult run(ApiCall call) throws Exception {
                if (call.tenantId != tenantId) {
                    throw new IllegalStateException("A preview reads only its own workspace's requests.");
                }
                if (++made[0] > MAX_CALLS) {
                    throw new IllegalStateException(String.format("A preview makes at most %d API calls.", MAX_CALLS));
                }
                String why = readOnlyRequest(tenantId, call.requestId);
                if (why != null) {
                    throw new IllegalStateException(why);
                }
                call.stepKey = "preview";
                return real.run(call);
            }
        };
    }

    // ----------------------------------------------------------------------------------------------------------- masking

    private static final Pattern PERSONAL = Pattern.compile("(?i).*(name|birth|dob|ssn|social|address|street|city|zip|postal|phone|mobile|fax|email|"
        + "e_mail|mrn|medical_record|record_number|member_id|patient_id|subscriber|passport|licen[cs]e|account|card|iban|ip_address|device_id).*");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern NUMBER_LIKE = Pattern.compile("^[+(]?[0-9][0-9 ()./-]{7,}[0-9]$");

    /** A row with a person's details masked to their form (letters x/X, digits 9); the columns masked are added to `masked`. */
    static Map<String, Object> mask(Map<String, Object> row, Set<String> masked) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            Object v = cell.getValue();
            boolean personal = PERSONAL.matcher(cell.getKey()).matches() && v != null && !(v instanceof Boolean);
            boolean looks = v instanceof String && (EMAIL.matcher((String) v).matches() || NUMBER_LIKE.matcher((String) v).matches());
            if (personal || looks) {
                masked.add(cell.getKey());
                out.put(cell.getKey(), form(String.valueOf(v)));
            } else {
                out.put(cell.getKey(), v);
            }
        }
        return out;
    }

    static String form(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length() && b.length() < 24; i++) {
            char c = text.charAt(i);
            b.append(Character.isDigit(c) ? '9' : Character.isUpperCase(c) ? 'X' : Character.isLetter(c) ? 'x' : c);
        }
        return text.length() > 24 ? b + "..." : b.toString();
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    private static long bytes(List<Map<String, Object>> rows, Map<String, List<Map<String, Object>>> inputs) {
        long n = String.valueOf(rows).getBytes(StandardCharsets.UTF_8).length;
        for (List<Map<String, Object>> in : inputs.values()) {
            n += String.valueOf(in).getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }

    private static Long parse(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException notOne) {
            return null;
        }
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private static ResponseDto refused(String message) {
        return new ResponseDto(ProcessUtil.ERROR, message);
    }

    /** The step's world in a preview: its rows and inputs in memory; nothing kept, nothing recorded, no run. */
    static final class PreviewContext implements StepContext {
        final long tenantId;
        final Map<String, Object> config;
        final Dataset input;
        final Map<String, List<Map<String, Object>>> inputs;
        final List<String> lines = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        PreviewContext(long tenantId, Map<String, Object> config, Dataset input, Map<String, List<Map<String, Object>>> inputs) {
            this.tenantId = tenantId;
            this.config = Collections.unmodifiableMap(config);
            this.input = input;
            this.inputs = inputs;
        }

        @Override
        public long tenantId() {
            return this.tenantId;
        }

        @Override
        public long jobQueueId() {
            return 0L;
        }

        @Override
        public int attempt() {
            return 1;
        }

        @Override
        public String stepKey() {
            return "preview";
        }

        @Override
        public int tryNumber() {
            return 1;
        }

        @Override
        public Map<String, Object> config() {
            return this.config;
        }

        @Override
        public Dataset input() {
            return this.input;
        }

        @Override
        public Dataset dataset(String stepKey) {
            List<Map<String, Object>> rows = this.inputs.get(stepKey);
            if (rows == null) {
                throw new IllegalArgumentException(String.format("No rows were given for step '%s' (inputs).", stepKey));
            }
            return Limits.require(Dataset.of(rows), "Step " + stepKey + "'s rows");
        }

        @Override
        public void keepFile(String fileName, byte[] content, long rows, List<String> columns) {
            throw new UnsupportedOperationException("A preview keeps no files.");
        }

        @Override
        public void log(String message) {
            if (this.lines.size() < 20) this.lines.add(message);
        }

        @Override
        public void warn(String message) {
            if (this.warnings.size() < 20) this.warnings.add(message);
        }
    }
}
