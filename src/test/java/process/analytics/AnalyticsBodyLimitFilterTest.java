package process.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outer guard on a recursion bomb.
 *
 * FilterCompiler's MAX_DEPTH cannot defend the binding that happens before it runs: FilterClause is
 * self-recursive, Jackson 2.11 has no depth cap, and a deeply nested body is a StackOverflowError
 * during binding -- an Error, so it passes every catch the controller has. These tests pin the byte
 * ceiling that stops such a body reaching the parser at all.
 *
 * @author Nabeel Ahmed
 */
class AnalyticsBodyLimitFilterTest {

    private final AnalyticsBodyLimitFilter filter = new AnalyticsBodyLimitFilter();

    private MockHttpServletResponse through(String path, int contentLength) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContentType("application/json");
        // Real bytes, because MockHttpServletRequest derives getContentLength() from the content
        // rather than from the header -- which is also what a real container does once the body
        // has arrived, so this is the honest shape of the check.
        request.setContent(new byte[contentLength]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        this.filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aBodyTooLargeToParseIsRefusedBeforeAnythingTriesToParseIt() throws Exception {
        MockHttpServletResponse response =
            through("/analytics.json/analyze", AnalyticsBodyLimitFilter.MAX_BODY_BYTES + 1);

        // A business refusal in the house envelope, not a 413 and not a dropped connection: the
        // caller is a person who has done something unusual, and they should be told what.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("too large to read");
    }

    @Test
    void anOrdinaryAnalysisPassesStraightThrough() throws Exception {
        // The control, and the one that matters most: a ceiling set too low would refuse real work
        // and would look identical in a suite that only tested the refusal. The largest analysis
        // this UI can build is a few kilobytes.
        MockHttpServletResponse response = through("/analytics.json/analyze", 8 * 1024);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void anUnrelatedEndpointIsNotThisFiltersBusiness() throws Exception {
        // A limit right for an analysis is wrong for a file upload, and this filter knows nothing
        // about those. Scoped rather than global.
        MockHttpServletResponse response =
            through("/storage.json/uploadObject", AnalyticsBodyLimitFilter.MAX_BODY_BYTES * 4);

        assertThat(response.getContentAsString()).isEmpty();
    }
}
