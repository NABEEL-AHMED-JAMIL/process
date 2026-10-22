package process.pipeline.registry;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One Task Registry entry as code declares it (MIG-231): what the task is called and does, what it reads and writes,
 * the config schema its step's form is generated from, which service does the work, the retry and timeout a step gets
 * when it says nothing, the role needed to add it to a pipeline, whether it is on unless a workspace says otherwise,
 * and its name as an AI tool (MIG-240).
 *
 * Built-ins are code, not rows: a task's schema and its implementation change together, in one commit. A workspace
 * may switch an overridable task off or on (task_registry_override); nothing else about an entry is per workspace.
 */
public final class TaskSpec {

    public static final String ROLE_USER = "TENANT_USER";
    public static final String ROLE_ADMIN = "TENANT_ADMIN";

    public static final String CORE = "core";
    public static final String INTEGRATION = "integration-service";
    public static final String STORAGE = "storage-service";
    public static final String NOTIFICATIONS = "notifications-service";
    public static final String AI = "ai-service";
    public static final String MEDIA = "media-service";
    public static final String WORKER = "worker";

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final String code;
    private final String name;
    private final TaskKind kind;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final Map<String, Object> configSchema;
    private final String backingService;
    private final int maxAttempts;
    private final int delaySeconds;
    private final Integer timeoutSeconds;
    private final String requiredRole;
    private final boolean enabledByDefault;
    private final boolean overridable;
    private final String aiToolName;
    private final boolean repeatSafe;
    /** MIG-420: what the task does with a run's items, when its config says so (itemsWhen); else PASS. */
    private final ItemRole itemRole;
    private final String itemsWhenField;
    private final List<Object> itemsWhenValues;
    private final String itemIdentity;
    private final String itemSubKey;

    private TaskSpec(Builder builder) {
        this.code = Objects.requireNonNull(builder.code, "code");
        this.name = Objects.requireNonNull(builder.name, "name");
        this.kind = Objects.requireNonNull(builder.kind, "kind");
        this.description = Objects.requireNonNull(builder.description, "description");
        this.inputSchema = builder.inputSchema;
        this.outputSchema = builder.outputSchema;
        this.configSchema = builder.configSchema == null ? JsonSchema.object().toMap() : builder.configSchema;
        this.backingService = builder.backingService;
        this.maxAttempts = builder.maxAttempts;
        this.delaySeconds = builder.delaySeconds;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.requiredRole = builder.requiredRole;
        this.enabledByDefault = builder.enabledByDefault;
        this.overridable = builder.overridable;
        this.aiToolName = builder.aiToolName == null ? builder.code : builder.aiToolName;
        this.repeatSafe = builder.repeatSafe != null ? builder.repeatSafe : this.kind == TaskKind.READ || this.kind == TaskKind.PROCESS;
        this.itemRole = builder.itemRole == null ? ItemRole.PASS : builder.itemRole;
        this.itemsWhenField = builder.itemsWhenField;
        this.itemsWhenValues = builder.itemsWhenValues;
        this.itemIdentity = builder.itemIdentity;
        this.itemSubKey = builder.itemSubKey;
        if (!TOOL_NAME.matcher(this.aiToolName).matches()) {
            throw new IllegalStateException("An AI tool name is snake_case: " + this.aiToolName);
        }
        if (!ROLE_USER.equals(this.requiredRole) && !ROLE_ADMIN.equals(this.requiredRole)) {
            throw new IllegalStateException("A task's required role is the tenant user's or the tenant administrator's: " + this.requiredRole);
        }
    }

    public static Builder builder(String code, String name, TaskKind kind) {
        return new Builder(code, name, kind);
    }

    /**
     * The entry a task that declares none has: a process step with a free config, on by default. Only tests' tasks
     * take it; every built-in declares its own.
     */
    public static TaskSpec minimal(String code, String description, boolean runsInEngine) {
        Map<String, Object> free = new LinkedHashMap<>();
        free.put("type", "object");
        return builder(code, code, runsInEngine ? TaskKind.PROCESS : TaskKind.LEGACY).description(description)
            .config(free).backing(CORE).build();
    }

    public String code() { return code; }

    public String name() { return name; }

    public TaskKind kind() { return kind; }

    public String description() { return description; }

    public Map<String, Object> inputSchema() { return inputSchema; }

    public Map<String, Object> outputSchema() { return outputSchema; }

    public Map<String, Object> configSchema() { return configSchema; }

    public String backingService() { return backingService; }

    public int maxAttempts() { return maxAttempts; }

    public int delaySeconds() { return delaySeconds; }

    /** Null: the pipeline's default timeout (settings.defaultTimeoutSeconds, else 600 s). */
    public Integer timeoutSeconds() { return timeoutSeconds; }

    public String requiredRole() { return requiredRole; }

    public boolean enabledByDefault() { return enabledByDefault; }

    public boolean overridable() { return overridable; }

    public String aiToolName() { return aiToolName; }

    /**
     * Owner 2026-10-21: a try can be made again without doing anything twice -- a read, a transform, or an output that
     * replaces what it wrote (the same file, the same report under its idempotency key). Such a step whose author set no
     * retry is tried again when it fails with an error that says to (StepEngine's passing errors). Reads and transforms
     * are; an output is only when its task says so.
     */
    public boolean repeatSafe() { return repeatSafe; }

    /** MIG-420: the task's item role as declared, whatever a step's config says. */
    public ItemRole itemRole() { return itemRole; }

    /**
     * MIG-420: what a step of this task does with the run's items, by its config: the declared role when the config field it
     * names holds one of its values (a missing field is its schema's default), else PASS.
     */
    public ItemRole itemRoleFor(Map<String, Object> config) {
        if (this.itemsWhenField == null || this.itemRole == ItemRole.PASS) {
            return this.itemRole;
        }
        Object value = config == null ? null : config.get(this.itemsWhenField);
        if (value == null) {
            value = this.defaultOf(this.itemsWhenField);
        }
        for (Object wanted : this.itemsWhenValues) {
            if (wanted != null && value != null && String.valueOf(wanted).equals(String.valueOf(value))) {
                return this.itemRole;
            }
        }
        return ItemRole.PASS;
    }

    /** MIG-420: the column a SOURCE step names each item by ("key", "extraction_id"); null for none. */
    public String itemIdentity() { return itemIdentity; }

    /** MIG-420: the column a FAN_OUT step names each sub-item by ("page"); null for none. */
    public String itemSubKey() { return itemSubKey; }

    @SuppressWarnings("unchecked")
    private Object defaultOf(String field) {
        Object properties = this.configSchema.get("properties");
        if (!(properties instanceof Map)) {
            return null;
        }
        Object property = ((Map<String, Object>) properties).get(field);
        return property instanceof Map ? ((Map<String, Object>) property).get("default") : null;
    }

    /** The entry as GET pipeline.json/steps/tasks answers it, before the workspace's enabled and availability. */
    public Map<String, Object> toEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", this.code);
        entry.put("name", this.name);
        entry.put("kind", this.kind.label());
        entry.put("description", this.description);
        entry.put("inputSchema", this.inputSchema);
        entry.put("outputSchema", this.outputSchema);
        entry.put("configSchema", this.configSchema);
        entry.put("backingService", this.backingService);
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("maxAttempts", this.maxAttempts);
        retry.put("delaySeconds", this.delaySeconds);
        entry.put("retry", retry);
        entry.put("timeoutSeconds", this.timeoutSeconds);
        entry.put("requiredPermission", this.requiredRole);
        entry.put("enabledByDefault", this.enabledByDefault);
        entry.put("overridable", this.overridable);
        entry.put("aiToolName", this.aiToolName);
        entry.put("repeatSafe", this.repeatSafe);
        // MIG-420: the Task Registry says what each task does with a run's items (the Items tab follows it).
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("role", this.itemRole.word());
        items.put("note", this.itemRole.note());
        if (this.itemsWhenField != null && this.itemRole != ItemRole.PASS) {
            items.put("when", Collections.singletonMap(this.itemsWhenField, this.itemsWhenValues));
        }
        if (this.itemIdentity != null) {
            items.put("identity", this.itemIdentity);
        }
        if (this.itemSubKey != null) {
            items.put("subKey", this.itemSubKey);
        }
        entry.put("items", items);
        return entry;
    }

    /** A dataset as a JSON Schema: rows, each an object of column: value. */
    public static Map<String, Object> rows(String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "object");
        row.put("additionalProperties", JsonSchema.scalar().toMap());
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("type", "array");
        rows.put("description", description);
        rows.put("items", row);
        return Collections.unmodifiableMap(rows);
    }

    public static final class Builder {
        private final String code;
        private final String name;
        private final TaskKind kind;
        private String description;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private Map<String, Object> configSchema;
        private String backingService = CORE;
        private int maxAttempts = 1;
        private int delaySeconds = 0;
        private Integer timeoutSeconds;
        private String requiredRole = ROLE_USER;
        private boolean enabledByDefault = true;
        private boolean overridable = true;
        private String aiToolName;
        private Boolean repeatSafe;
        private ItemRole itemRole;
        private String itemsWhenField;
        private List<Object> itemsWhenValues;
        private String itemIdentity;
        private String itemSubKey;

        private Builder(String code, String name, TaskKind kind) {
            this.code = code;
            this.name = name;
            this.kind = kind;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder input(Map<String, Object> schema) {
            this.inputSchema = schema;
            return this;
        }

        public Builder output(Map<String, Object> schema) {
            this.outputSchema = schema;
            return this;
        }

        public Builder config(Map<String, Object> schema) {
            this.configSchema = schema;
            return this;
        }

        public Builder config(JsonSchema schema) {
            this.configSchema = schema.toMap();
            return this;
        }

        public Builder backing(String service) {
            this.backingService = service;
            return this;
        }

        public Builder retry(int maxAttempts, int delaySeconds) {
            this.maxAttempts = maxAttempts;
            this.delaySeconds = delaySeconds;
            return this;
        }

        public Builder timeoutSeconds(Integer seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        public Builder requiredRole(String role) {
            this.requiredRole = role;
            return this;
        }

        public Builder enabledByDefault(boolean enabled) {
            this.enabledByDefault = enabled;
            return this;
        }

        public Builder overridable(boolean overridable) {
            this.overridable = overridable;
            return this;
        }

        public Builder aiToolName(String name) {
            this.aiToolName = name;
            return this;
        }

        /** Whether a try can be made again without doing anything twice; by default a read's or a transform's can. */
        public Builder repeatSafe(boolean safe) {
            this.repeatSafe = safe;
            return this;
        }

        /** MIG-420: what the task does with a run's items (the Run Items view). */
        public Builder items(ItemRole role) {
            this.itemRole = role;
            return this;
        }

        /** MIG-420: the role holds only when this config field is one of these values (else PASS). */
        public Builder itemsWhen(String field, Object... values) {
            this.itemsWhenField = field;
            this.itemsWhenValues = Arrays.asList(values);
            return this;
        }

        /** MIG-420: the column a SOURCE step names each item by. */
        public Builder itemIdentity(String column) {
            this.itemIdentity = column;
            return this;
        }

        /** MIG-420: the column a FAN_OUT step names each sub-item by. */
        public Builder itemSubKey(String column) {
            this.itemSubKey = column;
            return this;
        }

        public TaskSpec build() {
            return new TaskSpec(this);
        }
    }
}
