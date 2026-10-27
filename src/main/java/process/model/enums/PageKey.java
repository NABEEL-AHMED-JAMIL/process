package process.model.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The console pages an access profile can grant: the fixed catalogue, in the order the menu
 * shows them.
 *
 * A code constant rather than a table on purpose. A grant names a page; if the page were a
 * row, renaming or removing one would leave grants pointing at nothing, and a typo in a row
 * would be a page nobody can reach. Here the compiler knows the whole list, the console's
 * catalogue endpoint serves it verbatim, and a key the catalogue no longer carries is simply
 * ignored at resolution.
 *
 * Every page carries the API groups behind it, so the server can refuse the calls and not
 * merely hide the menu. Several groups are shared -- Source Tasks are read when a job is
 * edited, agents are read by the assistant on the jobs page -- so a group is open to a person
 * who holds ANY page naming it. What is deliberately absent is /storage.json: the object
 * browser is one of its callers, but so are profile pictures, task forms and analytics, and a
 * tenant user with no Browse files page must still be able to upload their own picture.
 *
 * Dashboard, Profile and Notifications are not here: they cannot be taken away, and neither
 * can the admin-only pages, which the roles already gate.
 *
 * @author Nabeel Ahmed
 */
public enum PageKey {

    // ZT-P11 (owner 2026-10-21, "Restrict."): a pipeline's definition -- the list, its fields and form, its steps and their
    // settings (bucket keys, request and prompt ids, a read's SQL) -- is the Pipelines or Schedules page's to read, so a
    // viewer or reviewer holding neither is refused it. Each read is named, not /pipeline.json whole: validate, preview,
    // /steps/tasks and /steps/reads (Sources') stay as they were. Home's count is /pipeline.json/count, open to everyone.
    JOBS("jobs", "Source Jobs", "Operations", "/operations/jobs",
        "/sourceJob.json", "/sourceTask.json", "/aiAgent.json", "/aiPrompt.json", "/fileChat.json",
        "/pipeline.json/listPipelines", "/pipeline.json/fields", "/pipeline.json/listForTopic", "/pipeline.json/definition",
        "/pipeline.json/formForPipeline", "/pipeline.json/get", "/pipeline.json/list", "/pipeline.json/listForms",
        "/pipeline.json/steps/definition"),
    TASKS("tasks", "Source Tasks", "Operations", "/operations/tasks",
        "/sourceTask.json",
        // The definition reads, steps/definition and list first: a refusal of them names Pipelines, not Prompts (PageGate.named).
        "/pipeline.json/steps/definition", "/pipeline.json/list", "/pipeline.json/listPipelines", "/pipeline.json/fields",
        "/pipeline.json/listForTopic", "/pipeline.json/definition", "/pipeline.json/formForPipeline", "/pipeline.json/get",
        "/pipeline.json/listForms"),
    // UX review 2026-10-12 (R2): a reviewer holding the Queue was refused the run they were sent to review. The run's
    // READS open to this page too -- its log, steps, a step's log, outputs, datasets, its review and the runs waiting for
    // review, and its AI answers -- and Core answers them only for a run the caller owns or reviews (RunReaders).
    // Deciding a review stays an administrator's (Core's @PreAuthorize) or the Task inbox's; a viewer holds neither page.
    QUEUE("queue", "Queue", "Operations", "/operations/queue",
        "/message.json",
        "/sourceJob.json/findSourceJobAuditLog", "/sourceJob.json/stepExecutions", "/sourceJob.json/stepLogs",
        "/sourceJob.json/runOutputs", "/sourceJob.json/runDataset", "/sourceJob.json/review", "/aiPrompt.json/runsForJob",
        // MIG-420: a run's items are run reads too (RunReaders decides whose); retrying them stays the Source Jobs page's.
        "/sourceJob.json/runItems"),
    // UX review 2026-10-13 (B3): Run analytics reads run rows through /message.json/fetchLogs only (its failed-runs list).
    // The Runs explorer's facets (/message.json/runFacets) and the run writes under /message.json are the Queue's.
    REPORTS("reports", "Reports", "Operations", "/operations/reports",
        "/report.json", "/message.json/fetchLogs"),
    OBJECTS("objects", "Browse files", "Object Browser", "/objects/files",
        "/fileShare.json", "/fileChat.json", "/aiAgent.json", "/aiPrompt.json"),
    ANALYTICS("analytics", "Analytics Studio", "Object Browser", "/objects/analytics",
        "/analytics.json", "/analyticsDataset.json", "/analyticsLibrary.json", "/analyticsExport.json",
        "/analyticsBenchmark.json", "/analyticsWorkspace.json"),
    // UX review 2026-10-12: Saved Analyses READS the boards -- the lists, a board, its tiles' saved queries and datasets, and
    // running a tile's query (analytics.json). Saving, pinning and deleting a board, a tile, a saved query or a dataset, and
    // writing back, are Edit dashboards' (DASHBOARDS_EDIT) or Analytics Studio's: a viewer holding Saved Analyses alone was
    // able to save tiles and could call pinChart directly. The first two of these lines are what the page always held.
    ANALYTICS_DASHBOARDS("analytics-dashboards", "Saved Analyses", "Object Browser", "/objects/analytics/dashboards",
        "/analytics.json", "/analyticsBenchmark.json/fetchRecentResults",
        "/analyticsDataset.json/fetchAllDatasets", "/analyticsDataset.json/fetchDatasetById",
        "/analyticsLibrary.json/fetchAllQueries", "/analyticsLibrary.json/fetchQueryById", "/analyticsLibrary.json/fetchRecentRuns",
        "/analyticsExport.json/download",
        "/analyticsWorkspace.json/fetchAllAnalyses", "/analyticsWorkspace.json/fetchAnalysisById",
        "/analyticsWorkspace.json/fetchAllDashboards", "/analyticsWorkspace.json/fetchDashboardById"),
    TOOLS_CONVERTER("tools-converter", "Document Converter", "Tools", "/tools/converter",
        "/documentConverter.json"),
    TOOLS_TRANSCRIPT("tools-transcript", "Audio Transcript", "Tools", "/tools/transcript",
        "/audioTranscript.json"),
    // Prompts replaced AI Agents on 2026-09-18 (V44 renames the grants). /aiAgent.json stays in
    // the group: the file chat and the job assistant still read prompts through its aliases.
    // ZT-P11: the AI Assistant (this page) lists the pipelines and reads a step definition to design and change one.
    AI_PROMPTS("ai-prompts", "Prompts", "Assistants", "/assistants/prompts",
        "/aiPrompt.json", "/aiAgent.json", "/pipeline.json/list", "/pipeline.json/steps/definition"),
    // Wave 5 Forms (lite): Core's own forms (/form.json: build, read, fill in) and what they collected
    // (/formSubmission.json). Identity-service's catalogue has had both keys since MIG-267; they gate these groups
    // once its PageKey names the same prefixes (HttpIdentity asks it only about a path this catalogue gates).
    FORMS("forms", "All forms", "Forms", "/forms/builder",
        "/form.json"),
    // Console review 2026-10-07 (H8): the Submissions page picks a form from /form.json/list and names its fields from
    // /form.json/fetch, so a reviewer holding Submissions alone was refused the whole page. Those two READS open to it as
    // well; building, saving, a form's status, sharing and filling in stay All forms'.
    FORM_SUBMISSIONS("form-submissions", "Submissions", "Forms", "/forms/submissions",
        "/formSubmission.json", "/form.json/list", "/form.json/fetch"),
    // UX review 2026-10-12: Saved Analyses' writes, split from the page that reads them -- identity-service's PageKey says
    // the same (these prefixes are analytics-service's; Core keeps the catalogue in step).
    DASHBOARDS_EDIT("dashboards-edit", "Edit dashboards", "Object Browser", "/objects/analytics/dashboards",
        "/analytics.json", "/analyticsDataset.json", "/analyticsLibrary.json", "/analyticsExport.json",
        "/analyticsBenchmark.json", "/analyticsWorkspace.json"),
    // UX review 2026-10-13 (I1): Connections › Sources lists what the workspace's pipelines read, from Core
    // (/pipeline.json/steps/reads). Identity-service's SOURCES has held the key since MIG-229 (integration-service's
    // /dataSource.json, gated at the gateway); Core names only its own path here, and identity's PageKey names it too.
    SOURCES("sources", "Sources", "Integration", "/integration/sources",
        "/pipeline.json/steps/reads"),
    // ZT-M21 (owner 2026-10-26): "Download sensitive files" -- the files a pipeline marked sensitive keeps (Save File and its
    // per-row files, an item's files and zips, a report's PDF, a run's outputs) are its real output and are never masked, so
    // downloading, viewing or previewing one is this permission's. A capability, not a screen: its prefix is served by
    // nothing; Core, media and ai ask the page gate for /sensitiveFiles.json/download. Identity-service's PageKey says the same.
    SENSITIVE_FILES("sensitive-files", "Download sensitive files", "Pipelines", "/runs/latest", "/sensitiveFiles.json");
    // BILLING was retired by MIG-34. It promised that an access profile could withhold Cost & usage
    // from a tenant admin, and nothing could: admins hold no profile, page access is evaluated for
    // tenant users only, and /billing.json -- billing-service's now, behind the gateway -- is
    // TENANT_ADMIN at the floor. Billing is role-gated only; V67 cleared the rows that named the key.

    /**
     * Calls under an enforced group that every signed-in person may make regardless of pages.
     * The profile screen asks /sourceJob.json/myActivity for the person's own recent runs, and
     * that is about them, not about the Source Jobs page.
     */
    private static final List<String> ALWAYS_OPEN = Collections.singletonList("/sourceJob.json/myActivity");

    private final String key;
    private final String label;
    private final String section;
    private final String route;
    private final List<String> apiPrefixes;

    PageKey(String key, String label, String section, String route, String... apiPrefixes) {
        this.key = key;
        this.label = label;
        this.section = section;
        this.route = route;
        this.apiPrefixes = Collections.unmodifiableList(Arrays.asList(apiPrefixes));
    }

    public String getKey() { return this.key; }

    public String getLabel() { return this.label; }

    public String getSection() { return this.section; }

    public String getRoute() { return this.route; }

    public List<String> getApiPrefixes() { return this.apiPrefixes; }

    /** The catalogue as the console sees it -- keys, never enum names. */
    public static Optional<PageKey> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = key.trim();
        return Arrays.stream(values()).filter(p -> p.key.equals(wanted)).findFirst();
    }

    public static Set<PageKey> all() {
        return EnumSet.allOf(PageKey.class);
    }

    /**
     * Whether an API path is gated at all, and if so by which pages.
     *
     * Empty means "not gated": the path belongs to no page (dashboard, users, storage, ...) or
     * is on the always-open list. Otherwise the caller must hold at least one of the pages
     * returned. The path is the servlet path, without the /api/v1 context.
     */
    public static Set<PageKey> pagesGating(String servletPath) {
        if (servletPath == null) {
            return Collections.emptySet();
        }
        for (String open : ALWAYS_OPEN) {
            if (servletPath.startsWith(open)) {
                return Collections.emptySet();
            }
        }
        Set<PageKey> gating = EnumSet.noneOf(PageKey.class);
        for (PageKey page : values()) {
            for (String prefix : page.apiPrefixes) {
                if (servletPath.equals(prefix) || servletPath.startsWith(prefix + "/")) {
                    gating.add(page);
                    break;
                }
            }
        }
        return gating;
    }
}
