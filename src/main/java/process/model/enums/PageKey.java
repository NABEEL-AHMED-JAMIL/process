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

    JOBS("jobs", "Source Jobs", "Operations", "/operations/jobs",
        "/sourceJob.json", "/sourceTask.json", "/aiAgent.json", "/aiPrompt.json", "/fileChat.json"),
    TASKS("tasks", "Source Tasks", "Operations", "/operations/tasks",
        "/sourceTask.json"),
    QUEUE("queue", "Queue", "Operations", "/operations/queue",
        "/message.json"),
    REPORTS("reports", "Reports", "Operations", "/operations/reports",
        "/report.json", "/message.json"),
    OBJECTS("objects", "Browse files", "Object Browser", "/objects/files",
        "/fileShare.json", "/fileChat.json", "/aiAgent.json", "/aiPrompt.json"),
    ANALYTICS("analytics", "Analytics Studio", "Object Browser", "/objects/analytics",
        "/analytics.json", "/analyticsDataset.json", "/analyticsLibrary.json", "/analyticsExport.json",
        "/analyticsBenchmark.json", "/analyticsWorkspace.json"),
    ANALYTICS_DASHBOARDS("analytics-dashboards", "Saved Analyses", "Object Browser", "/objects/analytics/dashboards",
        "/analytics.json", "/analyticsDataset.json", "/analyticsLibrary.json", "/analyticsExport.json",
        "/analyticsBenchmark.json", "/analyticsWorkspace.json"),
    TOOLS_CONVERTER("tools-converter", "Document Converter", "Tools", "/tools/converter",
        "/documentConverter.json"),
    TOOLS_TRANSCRIPT("tools-transcript", "Audio Transcript", "Tools", "/tools/transcript",
        "/audioTranscript.json"),
    // Prompts replaced AI Agents on 2026-09-18 (V44 renames the grants). /aiAgent.json stays in
    // the group: the file chat and the job assistant still read prompts through its aliases.
    AI_PROMPTS("ai-prompts", "Prompts", "Assistants", "/assistants/prompts",
        "/aiPrompt.json", "/aiAgent.json");

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
