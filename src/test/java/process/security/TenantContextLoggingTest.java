package process.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-43, process's part: every log line says whose request it is. The caller set on TenantContext
 * -- by the JWT filter for a request, by a job for its own work -- is copied into the logging MDC
 * beside the correlation id, and cleared with it, so a line written after the request never carries
 * the last caller's ids. The pattern prints all three, and names the host by its real name.
 */
class TenantContextLoggingTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theCallerIsOnEveryLogLineWhileSetAndGoneAfter() {
        TenantContext.set(2905L, "TENANT_ADMIN", 42L, "ops@tenant.test");
        assertThat(MDC.get("tenantId")).isEqualTo("2905");
        assertThat(MDC.get("appUserId")).isEqualTo("42");

        TenantContext.clear();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("appUserId")).isNull();
    }

    @Test
    void aPlatformAdminHasNoTenantAndSaysSo() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("appUserId")).isEqualTo("1000");
    }

    @Test
    void theLogPatternPrintsTheCorrelationTenantAndUserAndARealHostName() throws Exception {
        String logback = new String(Files.readAllBytes(Paths.get("src/main/resources/logback.xml")), StandardCharsets.UTF_8);
        assertThat(logback).doesNotContain("${hostName}");
        assertThat(logback).contains("%X{correlationId").contains("%X{tenantId").contains("%X{appUserId");
    }
}
