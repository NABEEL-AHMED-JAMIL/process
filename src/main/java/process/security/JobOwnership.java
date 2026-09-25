package process.security;

import process.model.pojo.SourceJob;

/**
 * The one place the job-ownership rule lives (owner decision 2026-09-24): inside a workspace, a
 * TENANT_USER sees and acts on their own jobs only; a TENANT_ADMIN and a PLATFORM_ADMIN see and act on
 * every job in their scope, as before.
 *
 * "Own" means the job names them: they created it (created_by) or it is assigned to them
 * (assigned_user_id). Their runs (job_queue), audit lines, schedule and statistics follow the job.
 * Tasks and task types are not jobs -- they are the shared, admin-made definitions a job is made from,
 * and stay visible to everyone in the workspace.
 *
 * The rule is applied three ways, all from here, and always on top of the tenant rule, never instead
 * of it:
 * - a JPQL read of SourceJob: the "jobOwnerFilter" Hibernate filter, which TenantFilterHelper turns on
 *   beside the tenant filter ({@link #FILTER_NAME});
 * - a native, string-built read (QueryService): {@link #sqlPredicate} on the source_job alias;
 * - a by-id read or write, which no Hibernate filter reaches: {@link #isVisibleToCaller}, and a job
 *   that fails it is answered exactly as a job that does not exist.
 *
 * It fails closed. Only the two admin roles are unrestricted; any other role, or none, is restricted,
 * and a restricted caller with no id of their own is bound to {@link #NO_OWNER_MATCHES}, which no row
 * carries, so they see nothing.
 *
 * @author Nabeel Ahmed
 */
public final class JobOwnership {

    /** The Hibernate filter on SourceJob that carries this rule into JPQL reads. */
    public static final String FILTER_NAME = "jobOwnerFilter";

    /** Its one parameter: the caller's app_user id. */
    public static final String FILTER_PARAMETER = "appUserId";

    /** The person nobody is: app_user ids come from a sequence, so no job names -1. */
    public static final long NO_OWNER_MATCHES = -1L;

    private JobOwnership() {}

    /** Whether this caller sees only the jobs that name them: anyone who is not a tenant or platform admin. */
    public static boolean isRestrictedToOwnJobs() {
        String role = TenantContext.getUserRole();
        return !("TENANT_ADMIN".equals(role) || "PLATFORM_ADMIN".equals(role));
    }

    /** The id a restricted caller's reads are bound to: their own, or {@link #NO_OWNER_MATCHES} without one. */
    public static long ownerId() {
        Long me = TenantContext.getAppUserId();
        return me == null ? NO_OWNER_MATCHES : me;
    }

    /**
     * May the caller see, and act on, this job? Its tenant first (TenantOwnership), then -- for a
     * restricted caller -- that it names them. A null job is not visible.
     */
    public static boolean isVisibleToCaller(SourceJob job) {
        return job != null && TenantOwnership.isOwnedByCaller(job.getTenantId())
            && namesCaller(job.getCreatedBy(), job.getAssignedUserId());
    }

    /** The ownership half alone, for a row read without its entity: true for an unrestricted caller. */
    public static boolean namesCaller(Long createdBy, Long assignedUserId) {
        if (!isRestrictedToOwnJobs()) {
            return true;
        }
        long me = ownerId();
        return me != NO_OWNER_MATCHES && (Long.valueOf(me).equals(createdBy) || Long.valueOf(me).equals(assignedUserId));
    }

    /**
     * The ownership predicate for a native query, on the given source_job alias, to append after the
     * tenant predicate: empty for an unrestricted caller. The id is the token's, a long, never text from
     * the request.
     */
    public static String sqlPredicate(String sourceJobAlias) {
        if (!isRestrictedToOwnJobs()) {
            return "";
        }
        long me = ownerId();
        if (me == NO_OWNER_MATCHES) {
            return " and 1 = 0 ";
        }
        return String.format(" and (%1$s.created_by = %2$d or %1$s.assigned_user_id = %2$d) ", sourceJobAlias, me);
    }

}
