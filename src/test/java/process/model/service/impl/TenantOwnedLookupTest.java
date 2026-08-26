package process.model.service.impl;

import org.junit.jupiter.api.Test;
import process.model.pojo.LookupData;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which lookup families belong to a tenant, and which belong to the platform.
 *
 * One predicate decides three separate things -- whether a tenant admin may add an entry,
 * whether the entry is stamped with their tenant, and whether they see anybody else's. Getting
 * the set wrong in either direction is quiet: too narrow and tenants cannot manage their own
 * pipelines, too wide and engine state is duplicated per tenant, which would give the scheduler
 * several different ideas of when it last ran.
 */
public class TenantOwnedLookupTest {

    private boolean isTenantOwned(String lookupType) throws Exception {
        LookupData parent = new LookupData();
        parent.setLookupType(lookupType);
        Method method = SettingServiceImpl.class
            .getDeclaredMethod("isTenantOwned", LookupData.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, parent);
    }

    @Test
    void nothingInTheseFamiliesIsSharedBetweenTenants() throws Exception {
        for (String type : new String[] {
            "BUCKET_LIST", "PIPELINE_IDS", "PIPELINE_HOME_PAGES", "TASK_GROUPS" }) {
            assertTrue(isTenantOwned(type), type + " should belong to the tenant that made it");
        }
    }

    @Test
    void engineStateStaysOnThePlatform() throws Exception {
        // Per-tenant copies of these would break the scheduler rather than isolate anything.
        for (String type : new String[] {
            "SCHEDULER_LAST_RUN_TIME", "QUEUE_FETCH_LIMIT", "AUDIT_LOG_SYNC_LAST_RUN_TIME",
            "AI_PROVIDER", "EMAIL_RECEIVER" }) {
            assertFalse(isTenantOwned(type), type + " must stay platform-level");
        }
    }

    private boolean isPlatformOnly(String lookupType) throws Exception {
        LookupData row = new LookupData();
        row.setLookupType(lookupType);
        Method method = SettingServiceImpl.class
            .getDeclaredMethod("isPlatformOnly", LookupData.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, row);
    }

    @Test
    void engineDialsAreVisibleOnlyToThePlatformAdmin() throws Exception {
        // Two are the timestamps the scheduler and audit sync resume from, one caps the
        // dispatcher, one names where system mail goes. None is a workspace setting.
        for (String type : new String[] {
            "QUEUE_FETCH_LIMIT", "SCHEDULER_LAST_RUN_TIME",
            "AUDIT_LOG_SYNC_LAST_RUN_TIME", "EMAIL_RECEIVER" }) {
            assertTrue(isPlatformOnly(type), type + " should be platform-only");
        }
    }

    @Test
    void whatATenantWorksWithIsNotPlatformOnly() throws Exception {
        for (String type : new String[] {
            "BUCKET_LIST", "PIPELINE_IDS", "PIPELINE_HOME_PAGES", "TASK_GROUPS", "AI_PROVIDER" }) {
            assertFalse(isPlatformOnly(type), type + " should stay visible to a tenant");
        }
    }

    @Test
    void aChildIsJudgedByItsFamilyNotItsOwnLabel() throws Exception {
        // A child carries its own descriptive type -- "Hurricane Data" under TASK_GROUPS -- so
        // asking the row alone gives the wrong answer and would leak the parent's protection.
        LookupData parent = new LookupData();
        parent.setLookupType("SCHEDULER_LAST_RUN_TIME");
        LookupData child = new LookupData();
        child.setLookupType("some descriptive label");
        child.setParent(parent);
        Method method = SettingServiceImpl.class
            .getDeclaredMethod("isPlatformOnly", LookupData.class);
        method.setAccessible(true);
        assertTrue((boolean) method.invoke(null, child),
            "a child of a platform-only family is platform-only");
    }

    @Test
    void anUnknownTypeIsNotTenantOwned() throws Exception {
        // Default deny: a family added later is platform-level until somebody decides otherwise.
        assertFalse(isTenantOwned("SOMETHING_ADDED_LATER"));
    }

    @Test
    void aMissingParentIsNotTenantOwned() throws Exception {
        Method method = SettingServiceImpl.class
            .getDeclaredMethod("isTenantOwned", LookupData.class);
        method.setAccessible(true);
        assertFalse((boolean) method.invoke(null, new Object[] { null }));
    }
}
