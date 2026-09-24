package process.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import process.billing.BillingService;
import process.billing.MeterClient;
import process.config.MethodSecurityConfig;
import process.engine.cron.UsageMeasurerCron;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-16 (DEF-022): who may reach which billing endpoint is declared, not decided in method bodies,
 * and proven here through the real method-security proxy and role hierarchy -- not by reading the
 * annotations alone.
 *
 * The trap: a method-level @PreAuthorize REPLACES the class-level hasRole('TENANT_ADMIN'), it does
 * not add to it. hasRole('PLATFORM_ADMIN') keeps everything the class rule refused, because the
 * hierarchy puts PLATFORM_ADMIN above TENANT_ADMIN; the tenant-user sweep below proves it for every
 * endpoint.
 */
class BillingAuthorizationTest {

    /** The endpoints only the platform reaches, by method name. A new one belongs here, or it is a tenant admin's. */
    private static final Set<String> PLATFORM_ONLY = new TreeSet<>(Arrays.asList(
        "draft", "addLine", "issue", "voidInvoice", "creditNote", "verifyPayment", "analytics", "closeMonth", "measure",
        "rateCards", "saveRateCard", "refresh"));

    private final MeterClient meter = mock(MeterClient.class);
    private final BillingService billing = mock(BillingService.class);
    private AnnotationConfigApplicationContext context;
    private BillingRestApi api;

    @Configuration
    @Import(MethodSecurityConfig.class)
    static class Wiring {
        static MeterClient meter;
        static BillingService billing;

        @Bean
        BillingRestApi billingRestApi() {
            return new BillingRestApi(meter, mock(UsageMeasurerCron.class), mock(UserNameResolver.class), billing);
        }
    }

    @BeforeEach
    void proxy() {
        Wiring.meter = this.meter;
        Wiring.billing = this.billing;
        when(this.meter.isConfigured()).thenReturn(true);
        when(this.meter.rollup(anyInt())).thenReturn(new HashMap<>());
        when(this.meter.rollup(anyLong(), any(LocalDate.class))).thenReturn(new HashMap<>());
        when(this.meter.rateCards()).thenReturn(new HashMap<>());
        when(this.meter.saveRateCard(any())).thenReturn(new HashMap<>());
        this.context = new AnnotationConfigApplicationContext(Wiring.class);
        this.api = this.context.getBean(BillingRestApi.class);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        if (this.context != null) {
            this.context.close();
        }
    }

    private static void signIn(String role, Long tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("caller", "n/a",
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
        TenantContext.set(tenantId, role, 7L, "caller");
    }

    private static Map<String, Object> card() {
        Map<String, Object> card = new HashMap<>();
        card.put("name", "v2");
        card.put("effective_from", "2026-10-01");
        return card;
    }

    // ---- the three that were decided in the body --------------------------------------------------

    @Test
    void aTenantAdminCannotRollUpThePlatformReadEveryCardOrChangeTheCalculation() {
        signIn("TENANT_ADMIN", 2905L);

        assertThatThrownBy(() -> this.api.refresh()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> this.api.rateCards()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> this.api.saveRateCard(card())).isInstanceOf(AccessDeniedException.class);
        verify(this.meter, never()).rollup(anyInt());
        verify(this.meter, never()).saveRateCard(any());
    }

    @Test
    void aPlatformAdminReachesAllThree() {
        signIn("PLATFORM_ADMIN", null);

        this.api.refresh();
        this.api.rateCards();
        this.api.saveRateCard(card());

        verify(this.meter).rollup(48);
        verify(this.meter).rateCards();
        verify(this.meter).saveRateCard(any());
    }

    /** Business rule kept: a tenant admin's Refresh prices their own workspace's latest events -- and only theirs. */
    @Test
    void aTenantAdminRefreshesTheirOwnWorkspaceOnly() {
        signIn("TENANT_ADMIN", 2905L);

        this.api.refreshWorkspace(4452L);

        LocalDate today = LocalDate.now();
        verify(this.meter).rollup(2905L, today);
        verify(this.meter).rollup(2905L, today.minusDays(1));
        verify(this.meter, never()).rollup(anyInt());
        verify(this.meter, never()).rollup(4452L, today);
    }

    /** A workspace's own card stays readable to its admin: the one rate-card read tenant admins are entitled to. */
    @Test
    void aTenantAdminStillReadsTheCardThatPricesTheirWorkspace() {
        signIn("TENANT_ADMIN", 2905L);

        this.api.rateCard(null, null, null);

        verify(this.meter).rateCardFor(2905L, LocalDate.now());
    }

    // ---- every endpoint -------------------------------------------------------------------------

    /** What the class rule refused, every endpoint still refuses -- method-level rules included. */
    @Test
    void aTenantUserReachesNoBillingEndpoint() throws Exception {
        signIn("TENANT_USER", 2905L);
        for (Method endpoint : endpoints()) {
            assertThatThrownBy(() -> invoke(endpoint)).as(endpoint.getName()).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void aTenantAdminReachesExactlyTheEndpointsThatAreNotPlatformOnly() throws Exception {
        signIn("TENANT_ADMIN", 2905L);
        Set<String> refused = new TreeSet<>();
        for (Method endpoint : endpoints()) {
            try {
                invoke(endpoint);
            } catch (AccessDeniedException denied) {
                refused.add(endpoint.getName());
            } catch (RuntimeException fromTheBodyWithNullArguments) {
                // Reached the body: allowed. What it did with null arguments is not this test's question.
            }
        }
        assertThat(refused).isEqualTo(PLATFORM_ONLY);
    }

    /** The audit: every endpoint carries a declarative guard, and none gates itself in its body. */
    @Test
    void everyEndpointIsGuardedByAnnotationAndNoneByItsBody() throws IOException {
        boolean classGuard = AnnotatedElementUtils.hasAnnotation(BillingRestApi.class, PreAuthorize.class);
        for (Method endpoint : endpoints()) {
            assertThat(classGuard || endpoint.isAnnotationPresent(PreAuthorize.class)).as(endpoint.getName()).isTrue();
        }
        Set<String> declaredPlatform = endpoints().stream()
            .filter(m -> m.isAnnotationPresent(PreAuthorize.class) && m.getAnnotation(PreAuthorize.class).value().contains("PLATFORM_ADMIN"))
            .map(Method::getName).collect(Collectors.toCollection(TreeSet::new));
        assertThat(declaredPlatform).isEqualTo(PLATFORM_ONLY);

        String source = new String(Files.readAllBytes(Paths.get("src/main/java/process/api/BillingRestApi.java")), StandardCharsets.UTF_8);
        assertThat(source).as("a whole endpoint refused in its body instead of by @PreAuthorize")
            .doesNotContainPattern("if \\(!TenantContext\\.isPlatformAdmin\\(\\)\\) return");
    }

    private static List<Method> endpoints() {
        return Arrays.stream(BillingRestApi.class.getDeclaredMethods())
            .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
            .collect(Collectors.toList());
    }

    private void invoke(Method endpoint) throws Exception {
        Method onProxy = this.api.getClass().getMethod(endpoint.getName(), endpoint.getParameterTypes());
        Object[] args = new Object[endpoint.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = endpoint.getParameterTypes()[i];
            // Object on every branch: a mixed ternary would unbox the null.
            args[i] = type == boolean.class ? (Object) Boolean.FALSE : type == int.class ? (Object) 0 : type == long.class ? (Object) 0L : null;
        }
        try {
            onProxy.invoke(this.api, args);
        } catch (InvocationTargetException thrown) {
            if (thrown.getCause() instanceof Exception) {
                throw (Exception) thrown.getCause();
            }
            throw thrown;
        }
    }
}
