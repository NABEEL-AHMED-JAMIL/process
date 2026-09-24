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
        assertThat(PageKey.pagesGating("/analyticsWorkspace.json/list"))
            .containsExactlyInAnyOrder(PageKey.ANALYTICS, PageKey.ANALYTICS_DASHBOARDS);
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
