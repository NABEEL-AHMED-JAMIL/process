package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import process.api.AppUserRestApi;
import process.api.AuthRestApi;
import process.api.PageAccessRestApi;
import process.api.TenantRequestRestApi;
import process.api.TenantRestApi;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-92: the cross-tenant probe covers every Identity endpoint, not the ones somebody remembered.
 *
 * Runs in every build, with or without a database. Each request-mapped method on Identity's controllers
 * is either probed in IdentityCrossTenantProbePostgresTest by its path, or platform-admin only (no tenant
 * reaches it to probe), or listed here with the reason it takes nothing a tenant could aim elsewhere.
 */
class IdentityProbeCoverageTest {

    private static final List<Class<?>> CONTROLLERS = Arrays.asList(AppUserRestApi.class, PageAccessRestApi.class,
        AuthRestApi.class, TenantRestApi.class, TenantRequestRestApi.class);

    private static final Map<String, String> NOTHING_TO_AIM = new HashMap<>();

    static {
        NOTHING_TO_AIM.put("auth.json/login", "anonymous; the answer is one sentence whatever the name (LoginRefusalCharacterisationTest)");
        NOTHING_TO_AIM.put("auth.json/refresh", "the token names the person; revoked tokens are TokenRevocationAcrossInstancesTest's");
        NOTHING_TO_AIM.put("auth.json/logout", "ends only the tokens presented; answers the same for anything");
        NOTHING_TO_AIM.put("tenantRequest.json/submit", "anonymous, creates a request for a tenant that does not exist yet; "
            + "the same acknowledgement either way (UsernameTakeoverTest)");
    }

    private static String prefix(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        return mapping.value()[0].replaceFirst("^/", "");
    }

    private static boolean platformOnly(Class<?> controller, Method method) {
        PreAuthorize own = method.getAnnotation(PreAuthorize.class);
        PreAuthorize rule = own != null ? own : controller.getAnnotation(PreAuthorize.class);
        return rule != null && rule.value().equals("hasRole('PLATFORM_ADMIN')");
    }

    @Test
    void everyEndpointIsProbedPlatformOnlyOrHasNothingToAim() throws IOException {
        String probe = new String(Files.readAllBytes(Paths.get(
            "src/test/java/process/schema/IdentityCrossTenantProbePostgresTest.java")), StandardCharsets.UTF_8);
        List<String> unprobed = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = method.getAnnotation(RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix(controller) + mapping.value()[0];
                if (platformOnly(controller, method) || NOTHING_TO_AIM.containsKey(path) || probe.contains("\"" + path)) {
                    continue;
                }
                unprobed.add(path);
            }
        }
        assertThat(unprobed).as("Identity endpoints a tenant can call that the cross-tenant probe does not").isEmpty();
    }
}
