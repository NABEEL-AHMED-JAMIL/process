package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.pojo.SourceTaskType;
import process.security.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That one workspace cannot see another's task types.
 *
 * <b>The rule this pins used to run the other way.</b> A task type with no owner meant "the
 * platform's, shared with every workspace", carried both by the list query
 * ("or source_task_type.tenant_id is null") and by the single-row helper below. The trouble was
 * that a NULL owner was also what an ACCIDENT produced: getSourceTaskType set the owner to
 * {@code isPlatformAdmin() ? null : getTenantId()}, so every task type a platform admin created
 * for one agency was shared with all of them. Five were -- "Test User 1 Task" through "Test User
 * 5 Task" -- and each carried its Kafka topic name onto every other workspace's settings screen.
 *
 * V39 removed the meaning rather than patching the accident: every task type has exactly one
 * owner, the column is NOT NULL, and there is no value that means "everyone". These tests are
 * what fails if the null-is-shared reading is reintroduced in either place.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class SettingServiceTaskTypeTenantTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Calls the private visibility helper, which is the single-row half of the rule. */
    private boolean visibleToCaller(Long ownerTenantId) {
        SourceTaskType taskType = new SourceTaskType();
        taskType.setTenantId(ownerTenantId);
        SettingServiceImpl service = newServiceWithoutDependencies();
        return (Boolean) ReflectionTestUtils.invokeMethod(
            service, "isSourceTaskTypeVisibleToCaller", taskType);
    }

    private boolean ownedByCaller(Long ownerTenantId) {
        SourceTaskType taskType = new SourceTaskType();
        taskType.setTenantId(ownerTenantId);
        SettingServiceImpl service = newServiceWithoutDependencies();
        return (Boolean) ReflectionTestUtils.invokeMethod(
            service, "isSourceTaskTypeOwnedByCaller", taskType);
    }

    /**
     * The two helpers under test read only TenantContext and the row handed to them, so the
     * service is built with every collaborator null rather than with eleven mocks that would
     * describe nothing about the rule.
     */
    private SettingServiceImpl newServiceWithoutDependencies() {
        return new SettingServiceImpl(null, null, null, null, null, null, null, null);
    }

    @Test
    void aTenantCannotSeeAnotherTenantsTaskType() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "a@example.com");

        assertThat(visibleToCaller(TENANT_B)).isFalse();
    }

    @Test
    void aTenantCanSeeItsOwn() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "a@example.com");

        assertThat(visibleToCaller(TENANT_A)).isTrue();
    }

    @Test
    void anUnownedTaskTypeIsNotSharedWithEveryone() {
        // The regression this file exists for. A null owner answered TRUE here, so five
        // per-agency task types appeared on every workspace's screen with their topic names.
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "a@example.com");

        assertThat(visibleToCaller(null)).isFalse();
    }

    @Test
    void aPlatformAdminStillSeesEverything() {
        // Deliberately unchanged: a platform admin operates across workspaces, and the Task Types
        // screen is where they do it.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        assertThat(visibleToCaller(TENANT_A)).isTrue();
        assertThat(visibleToCaller(TENANT_B)).isTrue();
        assertThat(visibleToCaller(null)).isTrue();
    }

    @Test
    void seeingIsNotOwning() {
        // Visibility and ownership are separate rules and must stay separate: a tenant may read
        // its own task type and must not be able to edit one that is nobody's.
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "a@example.com");

        assertThat(ownedByCaller(TENANT_A)).isTrue();
        assertThat(ownedByCaller(null)).isFalse();
        assertThat(ownedByCaller(TENANT_B)).isFalse();
    }

    @Test
    void aTenantUserIsNoDifferentFromATenantAdminHere() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "u@example.com");

        assertThat(visibleToCaller(TENANT_B)).isFalse();
        assertThat(visibleToCaller(null)).isFalse();
        assertThat(visibleToCaller(TENANT_A)).isTrue();
    }
}
