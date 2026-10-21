package process.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue's two lookups: key to page, and API path to the pages that gate it.
 *
 * @author Nabeel Ahmed
 */
public class PageKeyTest {

    @Test
    void keysAreUniqueAndRoundTrip() {
        for (PageKey page : PageKey.values()) {
            assertThat(PageKey.fromKey(page.getKey())).contains(page);
        }
        assertThat(PageKey.fromKey(" jobs ")).contains(PageKey.JOBS);
        assertThat(PageKey.fromKey("JOBS")).isEmpty();
        assertThat(PageKey.fromKey(null)).isEmpty();
    }

    @Test
    void aPathIsGatedByEveryPageThatNamesItsGroup() {
        assertThat(PageKey.pagesGating("/report.json/fetchReports")).containsExactly(PageKey.REPORTS);
        assertThat(PageKey.pagesGating("/message.json/fetchLogs")).containsExactlyInAnyOrder(PageKey.QUEUE, PageKey.REPORTS);
        assertThat(PageKey.pagesGating("/sourceTask.json/list")).containsExactlyInAnyOrder(PageKey.JOBS, PageKey.TASKS);
        // UX review 2026-10-12: Saved Analyses reads boards; their writes are Edit dashboards' (or the Studio's).
        assertThat(PageKey.pagesGating("/analyticsWorkspace.json/list"))
            .containsExactlyInAnyOrder(PageKey.ANALYTICS, PageKey.DASHBOARDS_EDIT);
        assertThat(PageKey.pagesGating("/analyticsWorkspace.json/fetchDashboardById"))
            .containsExactlyInAnyOrder(PageKey.ANALYTICS, PageKey.ANALYTICS_DASHBOARDS, PageKey.DASHBOARDS_EDIT);
        assertThat(PageKey.pagesGating("/analyticsWorkspace.json/pinChart"))
            .containsExactlyInAnyOrder(PageKey.ANALYTICS, PageKey.DASHBOARDS_EDIT);
        // UX review 2026-10-12 (R2): a run's reads open to the Queue too; running it does not.
        assertThat(PageKey.pagesGating("/sourceJob.json/stepExecutions")).containsExactlyInAnyOrder(PageKey.JOBS, PageKey.QUEUE);
        assertThat(PageKey.pagesGating("/sourceJob.json/review/waiting")).containsExactlyInAnyOrder(PageKey.JOBS, PageKey.QUEUE);
        assertThat(PageKey.pagesGating("/sourceJob.json/runSourceJob")).containsExactly(PageKey.JOBS);
        // Wave 5 Forms (lite): building and filling in is the page 'forms'; what they collected is 'form-submissions'.
        assertThat(PageKey.pagesGating("/form.json/submit")).containsExactly(PageKey.FORMS);
        assertThat(PageKey.pagesGating("/formSubmission.json/export")).containsExactly(PageKey.FORM_SUBMISSIONS);
        assertThat(PageKey.fromKey("form-submissions")).contains(PageKey.FORM_SUBMISSIONS);
        // Review 2026-10-07 (H8): Submissions reads the forms list and a form's fields too -- reads only.
        assertThat(PageKey.pagesGating("/form.json/list")).containsExactlyInAnyOrder(PageKey.FORMS, PageKey.FORM_SUBMISSIONS);
        assertThat(PageKey.pagesGating("/form.json/fetch")).containsExactlyInAnyOrder(PageKey.FORMS, PageKey.FORM_SUBMISSIONS);
        for (String write : new String[] {"/form.json/save", "/form.json/status", "/form.json/submit", "/form.json/upload",
            "/form.json/shareLinks/create", "/form.json/sharePolicy", "/form.json/linkableJobs", "/form.json/rebuildDataset", "/form.json/listX"}) {
            assertThat(PageKey.pagesGating(write)).as(write).containsExactly(PageKey.FORMS);
        }
    }

    /**
     * ZT-P11 (owner 2026-10-21, "Restrict."): a pipeline's definition is the Pipelines or Schedules page's -- a viewer or a
     * reviewer holding neither is refused it -- and the AI Assistant's page lists the pipelines and reads a step definition.
     * Home's count, a preview, a validation, the task list and Sources' reads are not definitions and stay as they were.
     */
    @Test
    void aPipelinesDefinitionIsThePipelinesOrSchedulesPages() {
        for (String read : new String[] {"/pipeline.json/listPipelines", "/pipeline.json/fields", "/pipeline.json/listForTopic",
            "/pipeline.json/definition", "/pipeline.json/formForPipeline", "/pipeline.json/get", "/pipeline.json/listForms",
            "/pipeline.json/steps/definition", "/pipeline.json/list"}) {
            assertThat(PageKey.pagesGating(read)).as(read).contains(PageKey.TASKS, PageKey.JOBS)
                .doesNotContain(PageKey.QUEUE, PageKey.REPORTS, PageKey.ANALYTICS_DASHBOARDS);
        }
        assertThat(PageKey.pagesGating("/pipeline.json/list")).containsExactlyInAnyOrder(PageKey.TASKS, PageKey.JOBS, PageKey.AI_PROMPTS);
        assertThat(PageKey.pagesGating("/pipeline.json/steps/definition"))
            .containsExactlyInAnyOrder(PageKey.TASKS, PageKey.JOBS, PageKey.AI_PROMPTS);
        assertThat(PageKey.pagesGating("/pipeline.json/listPipelines")).containsExactlyInAnyOrder(PageKey.TASKS, PageKey.JOBS);
        for (String open : new String[] {"/pipeline.json/count", "/pipeline.json/steps/preview", "/pipeline.json/steps/validate",
            "/pipeline.json/steps/tasks"}) {
            assertThat(PageKey.pagesGating(open)).as(open).isEmpty();
        }
        assertThat(PageKey.pagesGating("/pipeline.json/steps/reads")).containsExactly(PageKey.SOURCES);
    }

    @Test
    void prefixesMatchWholeSegmentsOnly() {
        // "/report.json" must not also gate a hypothetical "/report.jsonx" or "/reportX.json".
        assertThat(PageKey.pagesGating("/report.jsonx/anything")).isEmpty();
        assertThat(PageKey.pagesGating("/report.json")).containsExactly(PageKey.REPORTS);
    }

    @Test
    void whatIsDeliberatelyOpenStaysOpen() {
        assertThat(PageKey.pagesGating("/storage.json/listObjects")).isEmpty();
        assertThat(PageKey.pagesGating("/dashboard.json/summary")).isEmpty();
        assertThat(PageKey.pagesGating("/appUser.json/listUsers")).isEmpty();
        assertThat(PageKey.pagesGating("/sourceJob.json/myActivity")).isEmpty();
        // Role-gated only since MIG-34 retired PageKey.BILLING: TENANT_ADMIN at billing-service is the gate.
        assertThat(PageKey.pagesGating("/billing.json/summary")).isEmpty();
        assertThat(PageKey.pagesGating(null)).isEmpty();
    }
}
