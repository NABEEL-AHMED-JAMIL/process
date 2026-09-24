package process.model.pojo;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
     *
     * LookupData used to be here too (a process-wide cache rebuilt from inside tenant requests); MIG-167
     * retired the table, and its workspace-owned rows became TaskReference, which is filtered.
     *
     * AppUser used to be here, and is filtered now (MIG-13): the reads that legitimately cross
     * tenants -- sign-in, "is this name taken", the created-by name resolver, the internal user
     * directory -- are native queries the filter does not reach, each named for what it crosses.
     * Notification used to be here too; it is not a process entity any more. It left for
     * notifications-service and its own notifications_db (MIG-21), where tenant_id is NOT NULL and
     * every read is by recipient.
     */
    private static final List<String> NOT_SCOPED_BY_TENANT_FILTER =
        Arrays.asList("Tenant");

    private String conditionOf(Class<?> entity) {
        Filter filter = entity.getAnnotation(Filter.class);
        assertNotNull(filter, entity.getSimpleName() + " must declare the tenantFilter to be covered by it");
        assertEquals("tenantFilter", filter.name());
        return filter.condition();
    }

    @Test
    void aRowWithNoTenantBelongsToThePlatformAndStaysVisible() {
        // Shared task types and the platform's Kafka profile are rows with a null tenant_id that
        // every tenant is meant to use (dispatch still falls back to the Kafka one -- see
        // KafkaConnectionProfileRepository). A filter written as a plain equality would delete
        // them from every tenant's screen.
        for (Class<?> entity : new Class<?>[] { SourceTaskType.class, KafkaConnectionProfile.class }) {
            assertTrue(conditionOf(entity).contains("tenant_id is null"),
                entity.getSimpleName() + " has shared rows, so the filter has to admit a null tenant");
        }
    }

    /** MIG-13: a person, and a person's page exceptions, belong to exactly one tenant; a platform admin's row to none. */
    @Test
    void aPersonAndTheirExceptionsBelongToExactlyOneTenant() {
        assertEquals("tenant_id = :tenantId", conditionOf(AppUser.class));
        assertEquals("tenant_id = :tenantId", conditionOf(UserPageAccess.class));
    }

    /** The one Identity table that left: nothing in process may map it again. */
    @Test
    void notificationIsNoLongerAProcessEntity() {
        for (File source : pojoSources()) {
            assertTrue(!source.getName().equals("Notification.java"), "notification lives in notifications_db (MIG-21)");
        }
    }

    /** MIG-167: a configuration entry, a home page and a group belong to exactly one workspace; there is no shared row. */
    @Test
    void configurationAndTaskReferencesBelongToExactlyOneTenant() {
        assertEquals("tenant_id = :tenantId", conditionOf(PipelineConfig.class));
        assertEquals("tenant_id = :tenantId", conditionOf(TaskReference.class));
    }

    @Test
    void aRouteBelongsToExactlyOneTenant() {
        // tenant_id is not nullable on this one and there is no shared row to admit.
        assertEquals("tenant_id = :tenantId", conditionOf(TenantTaskTypeKafkaRoute.class));
    }

    @Test
    void aPipelineBelongsToExactlyOneTenant() {
        // Used to read like SourceTaskType/KafkaConnectionProfile above -- a null-tenant row was
        // "shared with every tenant." Moved to strict per-tenant filtering (2026-09-05): a task
        // form's XML-tag schema for a pipeline is workspace-specific the same way a storage or
        // Kafka connection is, not a shared taxonomy the way SourceTaskType genuinely is, and
        // nothing here has a dispatch-fallback use for a hidden platform default the way Kafka's
        // KafkaConnectionResolver does. The declaration itself is currently inert either way --
        // PipelineServiceImpl never calls TenantFilterHelper.enableIfNeeded -- but it should still
        // describe the rule the repository's own @Query methods actually enforce.
        assertEquals("tenant_id = :tenantId", conditionOf(Pipeline.class));
    }

    /**
     * The whole list, so a count quoted in a document can be checked against the code rather than the
     * other way round. 24 in the Phase 1 analysis; StorageConnection (MIG-68) and DocumentConverterTask
     * (Media) have left process since, InvoiceLine joined (MIG-47), and then the five billing entities left
     * with billing-service (MIG-88/89): 18. AppUser and UserPageAccess joined (MIG-13).
     */
    @Test
    void theFilteredEntitiesAreExactlyThese() throws Exception {
        List<String> filtered = new ArrayList<>();
        for (File source : pojoSources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (text.contains("@Filter(name = \"tenantFilter\"")) {
                filtered.add(source.getName().replace(".java", ""));
            }
        }
        Collections.sort(filtered);
        // MIG-29 (V102): JobAuditLogs, JobQueue, PipelineField, Scheduler and SourceTaskPayload carry their parent's
        // tenant_id now, and the filter with it.
        // MIG-167: PipelineConfig (a workspace's configuration and secrets) and TaskReference (its home pages and groups).
        assertEquals(Arrays.asList("AppUser", "JobAuditLogs", "JobQueue", "KafkaConnectionProfile", "PageAccessProfile", "Pipeline",
            "PipelineConfig", "PipelineField", "Scheduler", "SourceJob", "SourceTask", "SourceTaskPayload", "SourceTaskType",
            "TaskReference", "TenantTaskTypeKafkaRoute", "UserPageAccess"), filtered);
    }

    /**
     * MIG-29: a run, its schedule, its audit trail, a task's tags and a pipeline's fields belong to exactly their parent's
     * tenant -- tenant_id is NOT NULL on all five and there is no shared row to admit.
     */
    @Test
    void theChildTablesAreStrictlyTheirParentsTenant() {
        for (Class<?> entity : new Class<?>[] {JobQueue.class, Scheduler.class, JobAuditLogs.class, SourceTaskPayload.class, PipelineField.class}) {
            assertEquals("tenant_id = :tenantId", conditionOf(entity), entity.getSimpleName());
        }
    }

    /** An entity that imports the filter annotations without applying them reads as filtered and is not (DEF-165). */
    @Test
    void noEntityImportsTheFilterWithoutApplyingIt() throws Exception {
        List<String> misleading = new ArrayList<>();
        for (File source : pojoSources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            if (text.contains("import org.hibernate.annotations.Filter;") && !text.contains("@Filter(")) {
                misleading.add(source.getName().replace(".java", ""));
            }
        }
        assertTrue(misleading.isEmpty(), "imports Filter but applies none: " + misleading);
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
