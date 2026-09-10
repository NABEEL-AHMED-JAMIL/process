package process.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import process.api.AnalyticsBenchmarkRestApi;
import process.api.AnalyticsCanvasRestApi;
import process.api.AnalyticsRestApi;
import process.api.AnalyticsWorkspaceRestApi;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every analytics endpoint, against callers who should not reach it.
 *
 * <b>Document 15's "penetration-style authorization tests", made systematic.</b> Real ones existed
 * -- the schema_name cross-bucket exploit, the cross-tenant resolver bug, the statement-gate
 * evasion corpus -- and each was written after somebody thought of the attack. What was missing is
 * the property that no endpoint can be ADDED without being covered.
 *
 * <b>So the endpoint list is discovered by reflection, never typed out.</b> A hand-written list is
 * a list somebody forgets to add to, and the forgetting looks exactly like passing. Every
 * @RequestMapping method on the four analytics controllers is enumerated here, and a new one is
 * in this suite the moment it compiles.
 *
 * The three properties below are the ones an attacker actually tries first, in order.
 *
 * @author Nabeel Ahmed
 */
class AnalyticsAuthorizationE2EIT extends E2ESupport {

    /** Every controller that serves analytics. Adding a fifth here is the only manual step. */
    private static final List<Class<?>> CONTROLLERS = Arrays.asList(
        AnalyticsRestApi.class,
        AnalyticsWorkspaceRestApi.class,
        AnalyticsCanvasRestApi.class,
        AnalyticsBenchmarkRestApi.class);

    /** One endpoint: where it lives and how it is reached. */
    private static final class Endpoint {
        final String url;
        final RequestMethod method;
        final String describedBy;

        Endpoint(String url, RequestMethod method, String describedBy) {
            this.url = url;
            this.method = method;
            this.describedBy = describedBy;
        }
    }

    /**
     * Every mapped method on the analytics controllers, read off the annotations.
     *
     * The base path comes from the class mapping and the rest from the method's, which is exactly
     * how Spring composes them -- so a route this finds is a route that exists.
     */
    private static List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<Endpoint>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping base = controller.getAnnotation(RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapped = method.getAnnotation(RequestMapping.class);
                if (mapped == null) {
                    continue;
                }
                String path = mapped.value().length == 0 ? "" : mapped.value()[0];
                RequestMethod verb = mapped.method().length == 0
                    ? RequestMethod.GET : mapped.method()[0];
                // A path variable is filled with a harmless literal. MockMvc treats the URL as a
                // template and would otherwise refuse to expand {queryId}; what is being tested is
                // the guard in front of the route, and any value reaches it the same way.
                String url = (prefix + path).replaceAll("\\{[^}]+\\}", "probe");
                found.add(new Endpoint(url, verb,
                    controller.getSimpleName() + "#" + method.getName()));
            }
        }
        return found;
    }

    @Test
    void everyAnalyticsEndpointIsDiscovered() {
        // The suite is only as good as its enumeration, so the enumeration is asserted first. A
        // reflection walk that silently found nothing would make every test below vacuously green.
        List<Endpoint> all = endpoints();
        assertThat(all).hasSizeGreaterThanOrEqualTo(18);
        for (Endpoint endpoint : all) {
            assertThat(endpoint.url).as("%s has no route", endpoint.describedBy)
                .startsWith("/analytics");
        }
    }

    @Test
    void noAnalyticsEndpointAnswersAnUnauthenticatedCaller() throws Exception {
        // The first thing anybody tries. 401 or 403 -- which of the two is Spring's business; what
        // matters here is that none of them is a 200 carrying data.
        List<String> answered = new ArrayList<String>();
        for (Endpoint endpoint : endpoints()) {
            int status = this.mvc.perform(endpoint.method == RequestMethod.POST
                    ? post(endpoint.url).contentType("application/json").content("{}")
                    : get(endpoint.url))
                .andReturn().getResponse().getStatus();
            if (status != 401 && status != 403) {
                answered.add(endpoint.describedBy + " -> " + status);
            }
        }
        assertThat(answered).as("these answered a caller with no session").isEmpty();
    }

    @Test
    void atenantUserCannotReachAnotherWorkspacesSavedWork() throws Exception {
        /*
         * The second thing anybody tries, and the one this module has actually been bitten by:
         * DatasetResolver once matched a connection by alias without checking who owned it.
         *
         * A saved analysis belonging to one workspace, fetched by a signed-in user of another.
         * The refusal must not be a 500 either -- an exception leaking through would be a
         * different bug wearing the same clothes.
         */
        Tenant theirs = this.newTenant("owner");
        AppUser owner = this.newUser(UserRole.TENANT_ADMIN, theirs);

        long id = Long.parseLong(this.mvc.perform(this.postAs(owner,
                "/analyticsWorkspace.json/saveAnalysis",
                "{\"analysisName\":\"theirs\",\"connectionAlias\":\"etl-bucket\","
                    + "\"datasetPath\":\"a.csv\",\"analysisConfig\":\"{}\"}"))
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"analyticsAnalysisId\"\\s*:\\s*(\\d+).*", "$1"));

        AppUser stranger = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("stranger"));
        String body = this.mvc.perform(this.getAs(stranger,
                "/analyticsWorkspace.json/fetchAnalysisById?analyticsAnalysisId=" + id))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).as("a stranger read another workspace's saved analysis")
            .doesNotContain("\"theirs\"");
        assertThat(body).contains("ERROR");
    }

    @Test
    void aTenantUserCannotReachAplatformOnlyBenchmark() throws Exception {
        // Third: a role check rather than an ownership one. The benchmark endpoints exist to
        // measure the deployment, not one workspace's data.
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, this.newTenant("ordinary"));

        int status = this.mvc.perform(this.postAs(tenantUser,
                "/analyticsBenchmark.json/runBenchmark", "{}"))
            .andReturn().getResponse().getStatus();

        // Either refused outright, or answered with a business ERROR -- never a benchmark.
        if (status == 200) {
            String body = this.mvc.perform(this.postAs(tenantUser,
                    "/analyticsBenchmark.json/runBenchmark", "{}"))
                .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("ERROR");
        } else {
            assertThat(status).isIn(401, 403);
        }
    }
}
