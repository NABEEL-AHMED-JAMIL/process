package process.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.dto.AiPromptDto;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPromptRun;
import process.model.repository.AiPromptRunRepository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one prompt once: renders the template, checks the connection's caps, calls the
 * provider with retries, validates JSON output (one repair round), and writes the run row.
 * Try it from the editor and a pipeline step both come through here, so the two never
 * disagree about what a prompt does.
 *
 * The caps are the difference between a feature and a bill. Per connection, at most
 * `maxConcurrency` calls are in flight (the rest wait, in order); per day, once the tokens
 * spent through the connection reach its budget the step fails before the call is made.
 */
@Component
public class PromptRunner {

    private final Logger logger = LoggerFactory.getLogger(PromptRunner.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");
    private static final int MAX_INPUT_CHARS = 200_000;
    private static final int ATTEMPTS = 3;

    private final AiProviderGateway gateway;
    private final AiPromptRunRepository runs;
    private final Gson gson = new Gson();
    private final Map<Long, Semaphore> inFlight = new ConcurrentHashMap<>();

    public PromptRunner(AiProviderGateway gateway, AiPromptRunRepository runs) {
        this.gateway = gateway;
        this.runs = runs;
    }

    /** Everything a run needs, resolved by the caller (prompt or version, connection, key, values). */
    public static class Job {
        public Long tenantId;
        public Long promptId;
        public Integer promptVersion;
        public AiModelConnection connection;
        public String apiKey;
        public String model;
        public String system;
        public String template;
        public List<AiPromptDto.Variable> variables = new ArrayList<>();
        public Map<String, String> values = new HashMap<>();
        public String outputMode;
        public String outputSchema;
        public Double temperature;
        public Integer maxTokens;
        public String kind;           // try | run
        public Long jobQueueId;
        public String stepTag;
        public Long actor;
    }

    /** The placeholders a template names, in order of first appearance. */
    public static List<String> placeholders(String template) {
        List<String> names = new ArrayList<>();
        if (template == null) return names;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) if (!names.contains(m.group(1))) names.add(m.group(1));
        return names;
    }

    /** The template with every value in; a required variable left empty is a refusal, not a call. */
    public static String render(String template, List<AiPromptDto.Variable> variables, Map<String, String> values) {
        Map<String, String> known = new HashMap<>();
        for (AiPromptDto.Variable v : variables) {
            String value = values.get(v.name);
            if ((value == null || value.trim().isEmpty()) && Boolean.TRUE.equals(v.required)) {
                throw new IllegalArgumentException(String.format("Variable \"%s\" is required and has no value.", v.name));
            }
            known.put(v.name, value == null ? "" : value);
        }
        for (Map.Entry<String, String> e : values.entrySet()) known.putIfAbsent(e.getKey(), e.getValue() == null ? "" : e.getValue());
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(known.getOrDefault(m.group(1), "")));
        m.appendTail(out);
        return out.toString();
    }

    /** Runs the job and returns its row -- status "ok" with the output, or "failed" with the error. Never throws. */
    public AiPromptRun run(Job job) {
        AiPromptRun row = new AiPromptRun();
        row.setTenantId(job.tenantId); row.setPromptId(job.promptId); row.setPromptVersion(job.promptVersion);
        row.setConnectionId(job.connection == null ? null : job.connection.getConnectionId());
        row.setKind(job.kind); row.setJobQueueId(job.jobQueueId); row.setStepTag(job.stepTag); row.setCreatedBy(job.actor);
        long started = System.currentTimeMillis();
        try {
            String user = render(job.template, job.variables, job.values);
            if (user.length() > MAX_INPUT_CHARS) user = user.substring(0, MAX_INPUT_CHARS);
            // JSON output: the keys the schema names are said out loud, so the template need
            // not repeat them and a small model still knows what shape to answer in.
            String keys = "json".equals(job.outputMode) ? this.schemaKeys(job.outputSchema) : "";
            if (!keys.isEmpty()) user = user + "\n\nAnswer with a JSON object only, with these keys: " + keys + ".";
            else if ("json".equals(job.outputMode)) user = user + "\n\nAnswer with a JSON object only.";
            row.setRenderedInput((job.system == null || job.system.isEmpty() ? "" : "system: " + job.system + "\n\n") + "user: " + user);
            this.checkBudget(job.connection);
            AiProviderGateway.ChatRequest req = new AiProviderGateway.ChatRequest();
            req.provider = job.connection.getProvider(); req.apiKey = job.apiKey; req.apiEndpoint = job.connection.getApiEndpoint();
            req.model = job.model; req.system = job.system; req.user = user; req.jsonMode = "json".equals(job.outputMode);
            req.temperature = job.temperature; req.maxTokens = job.maxTokens;
            AiProviderGateway.ChatAnswer answer = this.withCap(job.connection, () -> this.callWithRetry(req, row));
            String text = answer.text;
            int tokensIn = Math.max(answer.tokensIn, 0), tokensOut = Math.max(answer.tokensOut, 0);
            row.setTokensIn(tokensIn); row.setTokensOut(tokensOut);
            if ("json".equals(job.outputMode)) {
                text = stripFences(text);
                String problem = this.jsonProblem(text, job.outputSchema);
                if (problem != null) {
                    // One repair round: the model is told what was wrong and asked for the JSON alone.
                    req.user = user + "\n\nYour previous answer was not valid: " + problem
                        + "\nReturn only the JSON object, nothing else.";
                    AiProviderGateway.ChatAnswer again = this.withCap(job.connection, () -> this.callWithRetry(req, row));
                    tokensIn += Math.max(again.tokensIn, 0); tokensOut += Math.max(again.tokensOut, 0);
                    // Counted even when the repair fails: both rounds were paid for.
                    row.setTokensIn(tokensIn); row.setTokensOut(tokensOut);
                    text = stripFences(again.text);
                    problem = this.jsonProblem(text, job.outputSchema);
                    if (problem != null) throw new IllegalStateException("The answer is not the JSON the prompt expects: " + problem);
                }
            }
            row.setOutput(text); row.setTokensIn(tokensIn); row.setTokensOut(tokensOut); row.setStatus("ok");
        } catch (Exception ex) {
            this.logger.warn("Prompt run failed (prompt {}, kind {})", job.promptId, job.kind, ex);
            row.setStatus("failed");
            String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            row.setError(m.length() > 2000 ? m.substring(0, 2000) : m);
        } finally {
            row.setLatencyMs((int) Math.min(System.currentTimeMillis() - started, Integer.MAX_VALUE));
        }
        return this.runs.save(row);
    }

    private void checkBudget(AiModelConnection c) {
        if (c == null) throw new IllegalStateException("No model connection: name one on the prompt or set a workspace default.");
        if (c.getDailyTokenBudget() == null) return;
        long spent = this.runs.tokensSince(c.getConnectionId(), Timestamp.valueOf(LocalDate.now(ZoneOffset.UTC).atStartOfDay()));
        if (spent >= c.getDailyTokenBudget()) {
            throw new IllegalStateException(String.format("Daily token budget reached on \"%s\" (%,d of %,d today). No call was made.",
                c.getName(), spent, c.getDailyTokenBudget()));
        }
    }

    private interface Call { AiProviderGateway.ChatAnswer go() throws Exception; }

    private AiProviderGateway.ChatAnswer withCap(AiModelConnection c, Call call) throws Exception {
        int cap = c.getMaxConcurrency() == null || c.getMaxConcurrency() < 1 ? 4 : c.getMaxConcurrency();
        Semaphore gate = this.inFlight.computeIfAbsent(c.getConnectionId(), id -> new Semaphore(cap, true));
        if (!gate.tryAcquire(5, TimeUnit.MINUTES)) {
            throw new IllegalStateException(String.format("\"%s\" has had %d calls in flight for five minutes; gave up waiting.", c.getName(), cap));
        }
        try { return call.go(); } finally { gate.release(); }
    }

    /** Three tries on a 429 or a 5xx, a second then three seconds apart; anything else fails at once. */
    private AiProviderGateway.ChatAnswer callWithRetry(AiProviderGateway.ChatRequest req, AiPromptRun row) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            row.setAttempts(attempt);
            try {
                return this.gateway.chat(req);
            } catch (AiProviderGateway.ProviderException ex) {
                last = ex;
                boolean transientFailure = ex.status == 429 || ex.status >= 500;
                if (!transientFailure || attempt == ATTEMPTS) throw ex;
                Thread.sleep(attempt == 1 ? 1000 : 3000);
            }
        }
        throw last;
    }

    /** Null when the text is the JSON the schema asks for; otherwise what is wrong, for the repair round. */
    String jsonProblem(String text, String schema) {
        JsonElement parsed;
        try { parsed = JsonParser.parseString(text); }
        catch (Exception ex) { return "not valid JSON"; }
        if (!parsed.isJsonObject()) return "not a JSON object";
        if (schema == null || schema.trim().isEmpty()) return null;
        JsonObject spec;
        try { spec = JsonParser.parseString(schema).getAsJsonObject(); }
        catch (Exception ex) { return null; }
        JsonObject obj = parsed.getAsJsonObject();
        List<String> missing = new ArrayList<>();
        if (spec.has("required") && spec.get("required").isJsonArray()) {
            for (JsonElement key : spec.getAsJsonArray("required")) if (!obj.has(key.getAsString())) missing.add(key.getAsString());
        } else if (spec.has("properties") && spec.get("properties").isJsonObject()) {
            for (String key : spec.getAsJsonObject("properties").keySet()) if (!obj.has(key)) missing.add(key);
        }
        return missing.isEmpty() ? null : "missing key(s): " + String.join(", ", missing);
    }

    /** Fences off, for callers outside the runner. */
    public static String stripFencesPublic(String text) { return stripFences(text); }

    /** "diagnosis, total_billed" from a schema's required list or its properties; "" when it names none. */
    String schemaKeys(String schema) {
        if (schema == null || schema.trim().isEmpty()) return "";
        try {
            JsonObject spec = JsonParser.parseString(schema).getAsJsonObject();
            List<String> keys = new ArrayList<>();
            if (spec.has("required") && spec.get("required").isJsonArray()) for (JsonElement k : spec.getAsJsonArray("required")) keys.add(k.getAsString());
            else if (spec.has("properties") && spec.get("properties").isJsonObject()) keys.addAll(spec.getAsJsonObject("properties").keySet());
            return String.join(", ", keys);
        } catch (Exception ex) { return ""; }
    }

    static String stripFences(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
        }
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }
}
