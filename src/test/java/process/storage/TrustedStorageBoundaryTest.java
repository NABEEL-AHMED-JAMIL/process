package process.storage;

import org.junit.jupiter.api.Test;
import process.model.service.StorageBrowserService;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage's privilege boundary, made explicit before Storage leaves process (MIG-52, MIG-65, DEF-058).
 *
 * It used to be one boolean: resolveService(bucket, trusted). The *ForWorkflow methods sat on the
 * same StorageBrowserService every other caller -- the REST controller included -- was handed, so any
 * code that could reach Storage could assert trust. Now the guarded operations and the trusted ones
 * are two interfaces; the trusted one has exactly four named callers, each of which builds its bucket
 * and key from a row or its own configuration; and no controller can reach it.
 */
class TrustedStorageBoundaryTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "process");

    /** The four, as MIG-52 names them. Adding a fifth is a decision, and this test is where it is made. */
    private static final List<String> TRUSTED_CALLERS = Arrays.asList(
        "config/KafkaTemplateProvider.java",
        "model/service/impl/KafkaSecretServiceImpl.java",
        "model/service/impl/AppUserServiceImpl.java",
        "billing/BillingService.java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String source(Path file) throws IOException {
        return new String(Files.readAllBytes(file));
    }

    @Test
    void theGuardedInterfaceOffersNoWayToTrust() {
        for (Method method : StorageBrowserService.class.getMethods()) {
            assertThat(method.getName()).as(method.toString()).doesNotEndWith("ForWorkflow");
            assertThat(method.getParameterTypes()).as(method.toString()).doesNotContain(boolean.class, Boolean.class, TrustedAccess.class);
        }
    }

    @Test
    void onlyTheFourNamedCallersReachTrustedStorage() throws IOException {
        List<String> reaching = new ArrayList<>();
        for (Path file : sources()) {
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("storage/") || relative.equals("model/service/impl/StorageBrowserServiceImpl.java")) {
                continue;
            }
            String text = source(file);
            if (text.contains("TrustedStorageOperations") || text.contains("ForWorkflow(")) {
                reaching.add(relative);
            }
        }
        assertThat(reaching).containsExactlyInAnyOrderElementsOf(TRUSTED_CALLERS);
    }

    @Test
    void noControllerReachesTrustedStorage() throws IOException {
        for (Path file : sources()) {
            String text = source(file);
            if (text.contains("@RestController") || text.contains("@Controller")) {
                assertThat(text).as(file.toString()).doesNotContain("TrustedStorageOperations").doesNotContain("TrustedAccess");
            }
        }
    }

    /** DEF-058: trust is a principal presented per call, not a boolean anywhere in Storage. */
    @Test
    void noBooleanDecidesTrustInsideStorage() throws IOException {
        String impl = source(MAIN.resolve("model/service/impl/StorageBrowserServiceImpl.java"));

        assertThat(impl).doesNotContain("boolean trusted").doesNotContain("resolveService(bucket, true)");
    }

    /** Each caller names itself: the principal is not a string any caller could type. */
    @Test
    void eachTrustedCallerPresentsItsOwnNamedPrincipal() throws IOException {
        assertThat(source(MAIN.resolve("config/KafkaTemplateProvider.java"))).contains("TrustedCaller.KAFKA_TEMPLATE_PROVIDER");
        assertThat(source(MAIN.resolve("model/service/impl/KafkaSecretServiceImpl.java"))).contains("TrustedCaller.KAFKA_SECRETS");
        assertThat(source(MAIN.resolve("model/service/impl/AppUserServiceImpl.java"))).contains("TrustedCaller.IDENTITY_AVATAR");
        assertThat(source(MAIN.resolve("billing/BillingService.java"))).contains("TrustedCaller.BILLING_DOCUMENTS");
    }
}
