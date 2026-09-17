package process.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That changing the model catalogue is a platform decision and reading it is not.
 *
 * <b>There is one Ollama server behind this controller and a model has no tenant at all.</b> The
 * class carried {@code hasRole('TENANT_ADMIN')} for all three endpoints, so any tenant
 * administrator -- 23 accounts across 9 workspaces on this deployment when it was found -- could
 * delete a model every other workspace depended on, or pull gigabytes onto shared disk. Deleting
 * the model an agent or a pipeline is configured against breaks that workspace silently, from
 * outside it, by someone with no part in the decision.
 *
 * Asserted by reflection rather than by calling the endpoints, because the failure this guards
 * against is an ANNOTATION being moved or removed -- putting one back on the class would restore
 * the old behaviour for every method at once, and a test that exercised the service would not
 * notice. Minting a tenant-admin token to prove the refusal would need a password this test does
 * not have; what it can prove is that the rule is declared where it has to be.
 *
 * @author Nabeel Ahmed
 */
public class OllamaAuthorizationTest {

    private static String ruleOn(String methodName) throws Exception {
        for (Method method : OllamaRestApi.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
                return rule == null ? null : rule.value();
            }
        }
        throw new AssertionError("no method named " + methodName + " on OllamaRestApi");
    }

    @Test
    void deletingAModelIsPlatformAdminOnly() throws Exception {
        assertThat(ruleOn("deleteModel")).isEqualTo("hasRole('PLATFORM_ADMIN')");
    }

    @Test
    void pullingAModelIsPlatformAdminOnly() throws Exception {
        // Spends shared disk and shared bandwidth on behalf of everyone.
        assertThat(ruleOn("pullModel")).isEqualTo("hasRole('PLATFORM_ADMIN')");
    }

    @Test
    void listingModelsStaysOpenToATenantAdmin() throws Exception {
        // Deliberately unchanged: an agent cannot be configured without knowing what exists, and
        // reading the catalogue harms nobody.
        assertThat(ruleOn("listModels")).isEqualTo("hasRole('TENANT_ADMIN')");
    }

    @Test
    void theClassDeclaresNoBlanketRule() {
        // The actual regression. A class-level @PreAuthorize applies to every method including
        // ones added later, and Spring lets the class-level rule stand where a method has none --
        // so a blanket TENANT_ADMIN here would quietly re-open both writes.
        assertThat(OllamaRestApi.class.getAnnotation(PreAuthorize.class))
            .as("authorization on this controller is per method, because its endpoints differ in blast radius")
            .isNull();
    }

    @Test
    void everyEndpointDeclaresItsOwnRule() throws Exception {
        // With no class-level default, a method that forgets the annotation is reachable by any
        // authenticated caller. This fails when an endpoint is added without deciding who may
        // call it, rather than leaving that to be noticed in production.
        for (Method method : OllamaRestApi.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class) == null) {
                continue;
            }
            assertThat(method.getAnnotation(PreAuthorize.class))
                .as("%s has no @PreAuthorize, so any authenticated caller can reach it", method.getName())
                .isNotNull();
        }
    }
}
