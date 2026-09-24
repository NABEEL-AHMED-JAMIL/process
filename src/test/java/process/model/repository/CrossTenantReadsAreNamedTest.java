package process.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-92 (P4, P5) and MIG-93: a read of Identity's tables that crosses tenants is a named grant, visible
 * in review, never an omission.
 *
 * The tenant filter reaches JPQL, not native SQL, so every native query in Identity's repositories
 * either names tenant_id or is named for what it crosses (…AcrossTenants) -- and the list of those is
 * written out here, so adding one is a diff to this file. The two paths the analysis found open by
 * omission are gone: findByUuid (unused) and bare findAll() calls, which read every tenant whenever the
 * filter happened to be off.
 */
class CrossTenantReadsAreNamedTest {

    private static final List<Class<?>> IDENTITY_REPOSITORIES = Arrays.asList(AppUserRepository.class,
        UserPageAccessRepository.class, PageAccessProfileRepository.class, TenantRepository.class,
        TenantRequestRepository.class);

    /** Every cross-tenant read, with why it must cross. */
    private static final Map<String, String> REVIEWED = new HashMap<>();

    static {
        REVIEWED.put("AppUserRepository.findLiveByUsernameAcrossTenants",
            "sign-in: there is no tenant yet, and a name is unique across the platform (MIG-17)");
        REVIEWED.put("AppUserRepository.isUsernameTakenAcrossTenants",
            "whether a name is free is a platform-wide fact (MIG-17)");
        REVIEWED.put("AppUserRepository.findAllByIdAcrossTenants",
            "names for ids: a tenant's row may have been made by a platform admin (MIG-13)");
        REVIEWED.put("AppUserRepository.findAllLiveAcrossTenants", "the platform admin's users screen, through TenantScope.AllTenants");
        REVIEWED.put("AppUserRepository.findActiveByRoleAcrossTenants",
            "telling the platform's admins about a new one; they belong to no tenant");
    }

    /** Native statements keyed by a person's id, or over a table with no tenant, not reads of a tenant's rows. */
    private static final Map<String, String> BY_ID = new HashMap<>();

    static {
        BY_ID.put("AppUserRepository.bumpTokenVersion", "ends one person's tokens; the caller scoped the person first");
        BY_ID.put("AppUserRepository.findTokenVersion", "a number, for the token check; returns no row");
        BY_ID.put("TenantRequestRepository.findOpenByEmail",
            "tenant_request rows belong to no tenant: a request is for a workspace that does not exist yet");
    }

    private static final Pattern TENANT_COLUMN = Pattern.compile("\\btenant_id\\b");

    @Test
    void everyNativeQueryNamesTheTenantOrIsANamedCrossTenantRead() {
        List<String> unnamed = new ArrayList<>();
        TreeSet<String> crossing = new TreeSet<>();
        for (Class<?> repository : IDENTITY_REPOSITORIES) {
            for (Method method : repository.getDeclaredMethods()) {
                String name = repository.getSimpleName() + "." + method.getName();
                if (method.getName().endsWith("AcrossTenants")) {
                    crossing.add(name);
                }
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                if (TENANT_COLUMN.matcher(query.value()).find() || method.getName().endsWith("AcrossTenants")
                    || BY_ID.containsKey(name)) {
                    continue;
                }
                unnamed.add(name);
            }
        }
        assertThat(unnamed).as("native SQL the tenant filter cannot reach, named for nothing it crosses").isEmpty();
        assertThat(crossing).as("the cross-tenant reads, each reviewed above").containsExactlyElementsOf(new TreeSet<>(REVIEWED.keySet()));
    }

    @Test
    void theTwoReadsOpenByOmissionAreGone() throws IOException {
        assertThat(Arrays.stream(AppUserRepository.class.getDeclaredMethods()).map(Method::getName))
            .doesNotContain("findByUuid");
        List<String> bareFindAll;
        Pattern call = Pattern.compile("(appUserRepository|users|userRepository|exceptionRepository)\\.findAll\\(\\)");
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            bareFindAll = walk.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return call.matcher(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).find();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }).map(Path::toString).collect(Collectors.toList());
        }
        assertThat(bareFindAll).as("findAll() on app_user or user_page_access: say which tenants instead").isEmpty();
    }
}
