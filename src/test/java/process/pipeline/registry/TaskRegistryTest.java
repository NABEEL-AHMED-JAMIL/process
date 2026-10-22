package process.pipeline.registry;

import org.junit.jupiter.api.Test;
import process.pipeline.AllTasks;
import process.pipeline.StepTask;
import process.pipeline.StepTasks;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-231: the Task Registry -- every built-in's entry, documented enough to build its form from, and whether a workspace
 * may use it: available here, switched on (its own switch, else the default), within the caller's role.
 */
class TaskRegistryTest {

    private static final long TENANT = 41L;

    private final AllTasks all = new AllTasks();
    private final StepTasks tasks = this.all.tasks();
    private final InMemoryTaskOverrideStore switches = new InMemoryTaskOverrideStore();
    private final TaskRegistry registry = new TaskRegistry(this.tasks, this.switches);

    @Test
    void theCoreReusableTasksAreRegisteredEachWithItsKind() {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (StepTask task : this.tasks.all()) {
            kinds.put(task.code(), task.spec().kind().label());
        }
        assertThat(kinds).containsOnly(
            entry("aggregate", "Process"), entry("ai_prompt", "Process"), entry("call_api", "Output"), entry("append", "Process"), entry("approval", "Process"),
            entry("limit", "Process"), entry("sort", "Process"), entry("send_webhook", "Output"), entry("compute", "Process"), entry("enrich", "Process"), entry("filter", "Process"), entry("join", "Process"),
            entry("legacy", "Legacy"), entry("measure_image", "Process"), entry("read_api", "Read"), entry("read_database", "Read"), entry("read_documents", "Read"), entry("read_file", "Read"),
            entry("read_s3", "Read"), entry("render_pdf", "Output"), entry("sample", "Read"), entry("save_file", "Output"), entry("select", "Process"),
            entry("send_notification", "Output"), entry("transform", "Process"), entry("upload_bucket", "Output"),
            entry("validate", "Process"), entry("write_database", "Output"),
            // Owner #16 (2026-10-19): the common tasks added.
            entry("dedupe", "Process"), entry("split_column", "Process"), entry("pivot", "Process"), entry("unpivot", "Process"),
            entry("fill_missing", "Process"),
            // 2026-10-19: imaging review -- a segmentation mask measured in pixels, and a pipeline's runs with their reviews.
            entry("measure_mask", "Process"), entry("read_runs", "Read"),
            // Report Studio phase 5: a Report Studio report from the rows, with the step's own design or a template.
            entry("generate_report", "Output"),
            // A file a row: a recording to text, a document to another format (media-service's transcriber and converter).
            entry("transcribe_audio", "Process"), entry("convert_file", "Process"),
            // MIG-420: a PDF into its pages, each a sub-item of its document.
            entry("split_pages", "Process"));
    }

    /**
     * Owner #16: a platform administrator's block is off whatever the workspace's switch says, refused naming the task and
     * the reason, and every entry says its effective state and who set it.
     */
    @Test
    void aBlockedTaskIsOffWhateverTheSwitchSaysAndTheEntrySaysWhoBlockedIt() {
        StepTask select = this.tasks.find("select").get();
        this.switches.set(TENANT, "select", true, 7L, "TENANT_ADMIN");
        this.switches.block(TENANT, "select", "Under review for this customer", 9001L);
        TaskControls mine = this.registry.controlsOf(TENANT);

        assertThat(this.registry.enabled(select, mine)).isFalse();
        assertThat(this.registry.effectiveState(select, mine)).isEqualTo("blocked");
        assertThat(this.registry.refusal(select, mine, "TENANT_ADMIN")).contains(
            "the task 'select' (Select columns) is blocked for this workspace by a platform administrator: Under review for this customer");
        assertThat(this.registry.enabled(select, this.registry.controlsOf(42L))).as("another workspace's block is not mine").isTrue();

        Map<String, Object> entry = this.registry.entries(TENANT).stream().filter(e -> "select".equals(e.get("code"))).findFirst().get();
        assertThat(entry).containsEntry("enabled", false).containsEntry("blocked", true).containsEntry("blockedBy", 9001L)
            .containsEntry("blockReason", "Under review for this customer").containsEntry("effectiveState", "blocked")
            .containsEntry("setBy", "platform").containsEntry("switchedBy", 7L).containsEntry("switchedByRole", "TENANT_ADMIN")
            .containsEntry("disabledReason", "Blocked for this workspace by a platform administrator: Under review for this customer");

        this.switches.unblock(TENANT, "select");
        assertThat(this.registry.enabled(select, this.registry.controlsOf(TENANT))).as("unblocked: the switch as it was").isTrue();
        Map<String, Object> after = this.registry.entries(TENANT).stream().filter(e -> "select".equals(e.get("code"))).findFirst().get();
        assertThat(after).containsEntry("effectiveState", "on").containsEntry("setBy", "workspace").containsEntry("blocked", false);
    }

    @Test
    void aPlatformSwitchSaysSoAndLegacyIsNeverBlocked() {
        this.switches.set(TENANT, "filter", false, 9001L, "PLATFORM_ADMIN");
        this.switches.block(TENANT, "legacy", null, 9001L);
        Map<String, Object> filter = this.registry.entries(TENANT).stream().filter(e -> "filter".equals(e.get("code"))).findFirst().get();
        assertThat(filter).containsEntry("effectiveState", "off").containsEntry("setBy", "platform")
            .containsEntry("disabledReason", "Switched off for this workspace by a platform administrator.");
        assertThat(this.registry.enabled(this.tasks.find("legacy").get(), this.registry.controlsOf(TENANT))).isTrue();
        Map<String, Object> plain = this.registry.entries(TENANT).stream().filter(e -> "sort".equals(e.get("code"))).findFirst().get();
        assertThat(plain).containsEntry("effectiveState", "on").containsEntry("setBy", "default").containsEntry("blocked", false);
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    /** The console generates each step's form from its config schema: every field has a title and a type. */
    @Test
    @SuppressWarnings("unchecked")
    void everyEntryIsDocumentedAndEveryAiToolNameIsUniqueSnakeCase() {
        Set<String> tools = new HashSet<>();
        for (StepTask task : this.tasks.all()) {
            TaskSpec spec = task.spec();
            assertThat(spec.description()).as(task.code()).isNotBlank();
            assertThat(spec.backingService()).as(task.code()).isNotBlank();
            assertThat(tools.add(spec.aiToolName())).as("AI tool name " + spec.aiToolName()).isTrue();
            assertThat(spec.aiToolName()).matches("^[a-z][a-z0-9_]*$");
            Map<String, Object> schema = spec.configSchema();
            assertThat(schema).as(task.code()).containsEntry("type", "object").containsEntry("additionalProperties", false);
            fields(task.code(), (Map<String, Object>) schema.get("properties"));
            if (spec.kind() != TaskKind.READ && spec.kind() != TaskKind.LEGACY) {
                assertThat(spec.inputSchema()).as(task.code() + " input").isNotNull();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void fields(String at, Map<String, Object> properties) {
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            Map<String, Object> field = (Map<String, Object>) property.getValue();
            assertThat(field).as(at + "." + property.getKey()).containsKeys("type", "title");
            Object items = field.get("items");
            if (items instanceof Map && ((Map<String, Object>) items).get("properties") instanceof Map) {
                fields(at + "." + property.getKey() + "[]", (Map<String, Object>) ((Map<String, Object>) items).get("properties"));
            }
        }
    }

    @Test
    void enabledIsAvailableAndTheWorkspacesSwitchElseTheDefault() {
        StepTask write = this.tasks.find("write_database").get();
        StepTask select = this.tasks.find("select").get();
        assertThat(this.registry.enabled(write, this.registry.overridesOf(TENANT))).as("off by default").isFalse();
        this.switches.set(TENANT, "write_database", true, 7L);
        this.switches.set(TENANT, "select", false, 7L);
        assertThat(this.registry.enabled(write, this.registry.overridesOf(TENANT))).isTrue();
        assertThat(this.registry.enabled(select, this.registry.overridesOf(TENANT))).isFalse();
        assertThat(this.registry.enabled(select, this.registry.overridesOf(42L))).as("another workspace's switch is not mine").isTrue();
        this.all.database.unavailable = "no write path";
        assertThat(this.registry.enabled(write, this.registry.overridesOf(TENANT))).as("switched on, but not available here").isFalse();
    }

    @Test
    void legacyAlwaysStaysRunnable() {
        this.switches.set(TENANT, "legacy", false, 7L);
        assertThat(this.registry.enabled(this.tasks.find("legacy").get(), this.registry.overridesOf(TENANT))).isTrue();
        assertThat(this.tasks.find("legacy").get().spec().overridable()).isFalse();
    }

    @Test
    void theRefusalSaysWhy() {
        Map<String, Boolean> mine = this.registry.overridesOf(TENANT);
        assertThat(this.registry.refusal(this.tasks.find("write_database").get(), mine, "TENANT_ADMIN"))
            .contains("the task 'write_database' is disabled in this workspace");
        assertThat(this.registry.refusal(this.tasks.find("upload_bucket").get(), mine, "TENANT_USER"))
            .contains("the task 'upload_bucket' needs the tenant administrator role");
        assertThat(this.registry.refusal(this.tasks.find("upload_bucket").get(), mine, "TENANT_ADMIN")).isEmpty();
        assertThat(this.registry.refusal(this.tasks.find("upload_bucket").get(), mine, null)).as("the engine checks no role").isEmpty();
        this.all.buckets.unavailable = "storage has no CORE_PIPELINES";
        assertThat(this.registry.refusal(this.tasks.find("read_s3").get(), mine, "TENANT_ADMIN"))
            .contains("the task 'read_s3' is not available: storage has no CORE_PIPELINES");
    }

    @Test
    void anEntryCarriesItsSchemaEnabledAndWhyNot() {
        this.switches.set(TENANT, "filter", false, 7L);
        List<Map<String, Object>> entries = this.registry.entries(TENANT);
        Map<String, Object> filter = entries.stream().filter(e -> "filter".equals(e.get("code"))).findFirst().get();
        assertThat(filter).containsEntry("enabled", false).containsEntry("overridden", true).containsEntry("available", true)
            .containsEntry("disabledReason", "Switched off in this workspace.").containsEntry("aiToolName", "filter_rows")
            .containsEntry("kind", "Process").containsEntry("requiredPermission", "TENANT_USER").containsKeys("configSchema",
                "inputSchema", "outputSchema", "retry", "timeoutSeconds", "backingService", "name", "description");
        Map<String, Object> write = entries.stream().filter(e -> "write_database".equals(e.get("code"))).findFirst().get();
        assertThat(write).containsEntry("enabled", false).containsEntry("enabledByDefault", false)
            .containsEntry("disabledReason", "Off by default; a workspace admin can switch it on.");
    }
}
