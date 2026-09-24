package process.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-93: Identity & Tenancy carved behind IdentityPort inside process, as Media and AI were before they
 * left. The classes below are Identity -- authentication, people, workspaces, workspace requests and page
 * access, over the six tables that stay together (app_user, tenant, tenant_request, page_access_profile,
 * page_access_profile_page, user_page_access). Nothing else in process names one of them: it asks the
 * port. When Identity leaves (MIG-107) this list is what goes, and the port's implementation changes.
 */
class IdentityPortBoundaryTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "process");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?process\\.([A-Za-z0-9_.]+);", Pattern.MULTILINE);
    private static final Pattern WILDCARD = Pattern.compile("^import process\\.([A-Za-z0-9_.]+)\\.\\*;", Pattern.MULTILINE);

    /** Identity, by path under process/. */
    static final List<String> IDENTITY = Arrays.asList(
        "model/pojo/AppUser.java", "model/pojo/Tenant.java", "model/pojo/TenantRequest.java",
        "model/pojo/PageAccessProfile.java", "model/pojo/UserPageAccess.java",
        "model/repository/AppUserRepository.java", "model/repository/TenantRepository.java",
        "model/repository/TenantRequestRepository.java", "model/repository/PageAccessProfileRepository.java",
        "model/repository/UserPageAccessRepository.java", "model/repository/ScopedAppUserReads.java",
        "model/service/AppUserService.java", "model/service/AuthService.java", "model/service/PageAccessService.java",
        "model/service/TenantService.java",
        "model/service/impl/AppUserServiceImpl.java", "model/service/impl/AuthServiceImpl.java",
        "model/service/impl/PageAccessServiceImpl.java", "model/service/impl/TenantServiceImpl.java",
        "model/service/impl/TenantRequestServiceImpl.java",
        "api/AppUserRestApi.java", "api/AuthRestApi.java", "api/PageAccessRestApi.java", "api/TenantRestApi.java",
        "api/TenantRequestRestApi.java",
        "security/PageGate.java", "security/PageAccessCache.java", "security/TokenRevocations.java",
        "security/signing/", "util/JwtUtil.java", "config/LoginGuardConfig.java",
        "identity/LocalIdentity.java", "identity/InternalJwksRestApi.java", "identity/InternalPageAccessRestApi.java");

    /** What the rest of process may name of Identity: the port. */
    private static final List<String> THE_PORT = Arrays.asList("identity.IdentityPort");

    private static boolean isIdentity(Path file) {
        String relative = MAIN.relativize(file).toString().replace('\\', '/');
        return IDENTITY.stream().anyMatch(relative::startsWith);
    }

    private static String asImport(String path) {
        return path.replace(".java", "").replace('/', '.');
    }

    /**
     * The six tables stay together: app_user.page_access_profile_id and page_access_profile.created_by
     * make one of the schema's two real foreign-key cycles, and splitting it would make it distributed.
     */
    @Test
    void theSixTablesAndTheirCycleStayOnOneSide() {
        for (String entity : new String[] {"AppUser", "Tenant", "TenantRequest", "PageAccessProfile", "UserPageAccess"}) {
            assertThat(IDENTITY).contains("model/pojo/" + entity + ".java");
        }
        // page_access_profile_page is PageAccessProfile's element collection, so it moves with it.
    }

    @Test
    void theIdentityClassesAreWhereThisListSays() {
        for (String path : IDENTITY) {
            assertThat(MAIN.resolve(path)).as(path).exists();
        }
    }

    @Test
    void theRestOfProcessReachesIdentityOnlyThroughThePort() throws IOException {
        Set<String> identity = IDENTITY.stream().filter(p -> p.endsWith(".java")).map(IdentityPortBoundaryTest::asImport)
            .collect(Collectors.toCollection(TreeSet::new));
        List<String> identityPackages = IDENTITY.stream().filter(p -> p.endsWith("/")).map(p -> asImport(p.substring(0, p.length() - 1)) + ".")
            .collect(Collectors.toList());
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : files) {
            if (isIdentity(file)) {
                continue;
            }
            String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = IMPORT.matcher(code);
            while (m.find()) {
                String imported = m.group(1);
                boolean isIdentity = identity.contains(imported) || identityPackages.stream().anyMatch(imported::startsWith);
                if (isIdentity && !THE_PORT.contains(imported)) {
                    offenders.add(MAIN.relativize(file) + " imports process." + imported);
                }
            }
            // A wildcard import of an Identity package names every class in it: say which ones are used.
            Matcher w = WILDCARD.matcher(code);
            while (w.find()) {
                String pkg = w.group(1) + ".";
                for (String cls : identity) {
                    if (cls.startsWith(pkg) && cls.indexOf('.', pkg.length()) < 0
                        && Pattern.compile("\\b" + cls.substring(pkg.length()) + "\\b").matcher(code).find()) {
                        offenders.add(MAIN.relativize(file) + " uses process." + cls + " through import " + w.group(1) + ".*");
                    }
                }
            }
            // A class in the same package needs no import: name it and it is used.
            String own = MAIN.relativize(file.getParent()).toString().replace('\\', '/') + "/";
            for (String path : IDENTITY) {
                if (path.endsWith(".java") && path.startsWith(own) && path.indexOf('/', own.length()) < 0) {
                    String simple = path.substring(own.length(), path.length() - ".java".length());
                    if (Pattern.compile("\\b" + simple + "\\b").matcher(code.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "")).find()) {
                        offenders.add(MAIN.relativize(file) + " uses " + simple + " from its own package");
                    }
                }
            }
        }
        assertThat(offenders).as("reaching past IdentityPort").isEmpty();
    }
}
