package process.model.pojo;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which entities the tenant filter actually reaches.
 *
 * TenantFilterHelper turns "tenantFilter" on for the whole session, and Hibernate applies it
 * only to the entities that declare it. An entity carrying tenant_id without that declaration
 * therefore reads as tenant-scoped at every call site and still returns every tenant's rows --
 * nothing is thrown and nothing is logged, so the gap is invisible until someone reads the
 * annotations. These tests make the set of declarations something that has to be changed on
 * purpose, and pin down which entities are deliberately left out and why.
 *
 * @author Nabeel Ahmed
 */
public class TenantFilterDeclarationTest {

    private static final Pattern TENANT_COLUMN = Pattern.compile("name\\s*=\\s*\"tenant_id\"");

    /**
     * Entities whose tenant_id is not a scoping column, or whose rows are scoped by something
     * narrower than the tenant. Filtering these would hide rows their owners are meant to see:
     *
     *   Tenant       -- tenant_id is its own primary key.
     *   AppUser      -- the login lookup and the created-by name resolver both read users
     *                   outside the caller's tenant, and the login one runs before there is a
     *                   tenant at all.
     *   LookupData   -- the parent rows are engine state, and the whole tree is loaded into a
     *                   process-wide cache that is rebuilt from inside tenant requests, so a
     *                   filtered rebuild would serve one tenant's view to everybody.
     *   Notification -- read by recipient, which is already narrower than by tenant.
     */
    private static final List<String> NOT_SCOPED_BY_TENANT_FILTER =
        Arrays.asList("Tenant", "AppUser", "LookupData", "Notification");

    private String conditionOf(Class<?> entity) {
        Filter filter = entity.getAnnotation(Filter.class);
        assertNotNull(filter, entity.getSimpleName() + " must declare the tenantFilter to be covered by it");
        assertEquals("tenantFilter", filter.name());
        return filter.condition();
    }

    @Test
    void aRowWithNoTenantBelongsToThePlatformAndStaysVisible() {
        // Shared task types, the platform's Kafka profile and the shared form definitions are
        // all rows with a null tenant_id that every tenant is meant to use. A filter written as
        // a plain equality would delete them from every tenant's screen.
        for (Class<?> entity : new Class<?>[] { SourceTaskType.class, KafkaConnectionProfile.class, TaskForm.class }) {
            assertTrue(conditionOf(entity).contains("tenant_id is null"),
                entity.getSimpleName() + " has shared rows, so the filter has to admit a null tenant");
        }
    }

    @Test
    void aRouteBelongsToExactlyOneTenant() {
        // tenant_id is not nullable on this one and there is no shared row to admit.
        assertEquals("tenant_id = :tenantId", conditionOf(TenantTaskTypeKafkaRoute.class));
    }

    @Test
    void everyTenantColumnIsEitherFilteredOrListedHere() throws Exception {
        List<String> unfiltered = new ArrayList<>();
        for (File source : pojoSources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (!TENANT_COLUMN.matcher(text).find() || text.contains("tenantFilter")) {
                continue;
            }
            String name = source.getName().replace(".java", "");
            if (!NOT_SCOPED_BY_TENANT_FILTER.contains(name)) {
                unfiltered.add(name);
            }
        }
        assertTrue(unfiltered.isEmpty(),
            "these entities carry tenant_id but no tenantFilter, so enableIfNeeded does nothing "
                + "for them -- declare the filter or say here why they are exempt: " + unfiltered);
    }

    private File[] pojoSources() {
        // Surefire runs from the module root; the second path is for a run from the parent.
        for (String candidate : new String[] {
            "src/main/java/process/model/pojo", "process/src/main/java/process/model/pojo" }) {
            File directory = new File(candidate);
            if (directory.isDirectory()) {
                return directory.listFiles((dir, name) -> name.endsWith(".java"));
            }
        }
        throw new IllegalStateException("Could not find process/model/pojo from " + System.getProperty("user.dir"));
    }

}
