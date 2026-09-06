package process.security;

import java.util.Objects;

/**
 * The one place the tenant-ownership rule lives. Every service had grown its own private copy of
 * it, and the copies had begun to disagree about the rows that carry no tenant at all -- which is
 * exactly the case that decides whether a tenant can reach the platform's records.
 *
 * The reading settled here is the one each entity already declares in its Hibernate tenant
 * filter, so a row unreachable in a list query stays unreachable by id:
 *
 * - A platform admin owns and sees everything; that is what the role is for.
 * - Every other caller must carry a tenant of its own. A context with no tenant owns nothing, so
 *   it is refused outright rather than being compared equal to the tenant-less rows.
 * - A row with a null tenantId is platform-owned, not ownerless. Every entity now filters on
 *   "tenant_id = :tenantId", which no null row satisfies, so such a row is invisible to a tenant
 *   in list queries and must stay unreachable by id too -- {@link #isOwnedByCaller}.
 * - {@link #isVisibleToCaller} exists for the one kind of read that still deliberately admits a
 *   platform-owned row: resolving a *default* to fall back on when a tenant has none of its own
 *   (KafkaConnectionResolver's Kafka dispatch is the one live example). That is a narrow,
 *   internal fallback, not a "list/read screen shows the platform's rows too" catalogue -- the
 *   last entity that worked the second way, TaskForm, was moved to strict per-tenant filtering
 *   (2026-09-05) once its "every tenant sees this" behavior was found to be untested and, for a
 *   task-payload schema, indistinguishable from the same problem Kafka Connections had already
 *   been fixed for. SourceTaskType still filters "tenant_id = :tenantId or tenant_id is null" as
 *   a genuine shared taxonomy (the kinds of task a job can run), not a per-workspace resource --
 *   that one was intentionally left as-is.
 *
 * @author Nabeel Ahmed
 * */
public final class TenantOwnership {

    private TenantOwnership() {}

    /**
     * May the caller act on a row owned by ownerTenantId? Answers no for a platform-owned row and
     * no for a caller with no tenant, so both fail closed.
     */
    public static boolean isOwnedByCaller(Long ownerTenantId) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        Long callerTenantId = TenantContext.getTenantId();
        return callerTenantId != null && Objects.equals(ownerTenantId, callerTenantId);
    }

    /**
     * May the caller read a row from one of the shared catalogues? Same rule, except that a
     * platform-owned row is published to every tenant rather than hidden from them.
     */
    public static boolean isVisibleToCaller(Long ownerTenantId) {
        return ownerTenantId == null || isOwnedByCaller(ownerTenantId);
    }

}
