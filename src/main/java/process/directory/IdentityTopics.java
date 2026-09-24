package process.directory;

/**
 * Identity's topics on the platform bus (MIG-166, MIG-153). identity-service's triggers write the events;
 * process's outbox publishes them (POST /internal/identity/events). Both are compacted and keyed by id: each
 * event is the whole current state of one workspace or one person.
 */
public final class IdentityTopics {

    /** A workspace made, renamed, suspended or deleted; keyed by tenant_id. */
    public static final String TENANT = "platform.identity.tenant.v1";

    /** A person made, renamed, moved, suspended or deleted; keyed by app_user_id. */
    public static final String USER = "platform.identity.user.v1";

    private IdentityTopics() {
    }
}
