package process.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The AI model a run's steps ask for (Wave 4, MIG-242's Core part): per AI step (its tag), an ai-service model option
 * id -- ai-service's "model profile". Two layers: the schedule's setting (source_job.model_profiles) and one run's "Run
 * with..." (job_queue.model_profiles), which wins for the steps it names. A step neither names asks for nothing, and
 * ai-service runs it on its step's default, exactly as before.
 *
 * Core only says what is asked. Whether the option may be used -- on the step's allowed list, in the run's workspace,
 * on an active connection, allowed by the data policy -- is ai-service's to decide at run time; a refusal there fails
 * the step (HTTP 422). Stored as a JSON object of strings, {"summary": "1204"}; read defensively, so a stored value
 * that is not one reads as nothing asked.
 */
public final class ModelProfiles {

    /** Where a step's profile came from, as run_ai_step.profile_source says it. */
    public static final String FROM_RUN = "run";

    public static final String FROM_SCHEDULE = "schedule";

    /** An ai-service model option id: a positive number, as ai-service parses it. */
    private static final Pattern OPTION_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private static final Gson GSON = new Gson();

    public static final ModelProfiles NONE = new ModelProfiles(Collections.emptyMap(), Collections.emptyMap());

    /** What one step asks for: the profile (an option id) and where it came from; both null for the step's default. */
    public static final class Asked {

        public static final Asked DEFAULT = new Asked(null, null);

        public final String profile;

        public final String source;

        Asked(String profile, String source) {
            this.profile = profile;
            this.source = source;
        }

        public boolean isDefault() {
            return this.profile == null;
        }
    }

    private final Map<String, String> schedule;

    private final Map<String, String> run;

    private ModelProfiles(Map<String, String> schedule, Map<String, String> run) {
        this.schedule = schedule;
        this.run = run;
    }

    /** The schedule's setting and the run's "Run with...", each as stored (null or blank for none). */
    public static ModelProfiles of(String scheduleJson, String runJson) {
        return new ModelProfiles(read(scheduleJson), read(runJson));
    }

    /** The run's first, then the schedule's, then the step's default. */
    public Asked forStep(String stepKey) {
        if (stepKey == null) {
            return Asked.DEFAULT;
        }
        if (this.run.containsKey(stepKey)) {
            return new Asked(this.run.get(stepKey), FROM_RUN);
        }
        if (this.schedule.containsKey(stepKey)) {
            return new Asked(this.schedule.get(stepKey), FROM_SCHEDULE);
        }
        return Asked.DEFAULT;
    }

    /** Whether a caller's option id is one ai-service could take as a model profile at all. */
    public static boolean isOptionId(String value) {
        return value != null && OPTION_ID.matcher(value.trim()).matches();
    }

    /** A stored value for this map -- steps in tag order, so the same choice is always the same text; null for none. */
    public static String write(Map<String, String> byStep) {
        if (byStep == null || byStep.isEmpty()) {
            return null;
        }
        return GSON.toJson(new TreeMap<>(byStep));
    }

    /** A stored value read back as step tag to option id; anything that is not a JSON object of ids reads as empty. */
    public static Map<String, String> read(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> byStep = new TreeMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(stored);
            if (!parsed.isJsonObject()) {
                return Collections.emptyMap();
            }
            JsonObject object = parsed.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                JsonElement value = e.getValue();
                if (value != null && value.isJsonPrimitive() && isOptionId(value.getAsString())) {
                    byStep.put(e.getKey(), value.getAsString().trim());
                }
            }
        } catch (RuntimeException unreadable) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(byStep);
    }
}
