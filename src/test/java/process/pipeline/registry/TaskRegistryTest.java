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
            entry("aggregate", "Process"), entry("ai_prompt", "Process"), entry("enrich", "Process"), entry("filter", "Process"), entry("join", "Process"),
            entry("legacy", "Legacy"), entry("read_api", "Read"), entry("read_database", "Read"), entry("read_file", "Read"),
            entry("read_s3", "Read"), entry("sample", "Read"), entry("save_file", "Output"), entry("select", "Process"),
            entry("send_notification", "Output"), entry("transform", "Process"), entry("upload_bucket", "Output"),
            entry("validate", "Process"), entry("write_database", "Output"));
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
