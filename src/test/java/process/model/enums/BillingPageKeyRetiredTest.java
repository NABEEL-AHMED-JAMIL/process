package process.model.enums;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-34, decided: PageKey.BILLING is retired, not implemented.
 *
 * Its comment promised that an access profile could withhold Cost &amp; usage from a tenant admin.
 * Nothing ever could: page access is evaluated for TENANT_USER only (PageGate, PageAccessInterceptor),
 * effectivePages gives every admin PageKey.all(), and an admin cannot even hold a profile
 * (reachablePerson, refuseUnusableProfile). /billing.json is TENANT_ADMIN at the floor -- in
 * billing-service now, behind the gateway (ADR-019) -- so no tenant user reaches it either, and the
 * key gated nobody. Implementing the promise is a feature (profiles for admins, and billing asking
 * Identity at request time), not a migration fix; keeping an inert key is how the question keeps
 * coming back. The written commitment is dropped on the record in MIG-34's notes.
 */
class BillingPageKeyRetiredTest {

    @Test
    void thereIsNoBillingPage() {
        assertThat(PageKey.fromKey("billing")).isEmpty();
        assertThat(PageKey.all()).extracting(PageKey::getKey).doesNotContain("billing");
    }

    /** Billing's API is role-gated only: the page gate leaves it to billing-service's TENANT_ADMIN check. */
    @Test
    void billingsApiIsGatedByNoPage() {
        assertThat(PageKey.pagesGating("/billing.json/summary")).isEmpty();
        assertThat(PageKey.pagesGating("/billing.json")).isEmpty();
    }

    /** The test the task asks for: no code path reads PageKey.BILLING, or asks for the page by its key. */
    @Test
    void noCodePathReadsIt() throws IOException {
        List<String> readers;
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            readers = walk.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    String code = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    return code.contains("PageKey.BILLING") || code.contains("fromKey(\"billing\")");
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }).map(Path::toString).collect(Collectors.toList());
        }
        assertThat(readers).isEmpty();
    }
}
