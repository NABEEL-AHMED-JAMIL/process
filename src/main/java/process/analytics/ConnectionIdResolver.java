package process.analytics;

/** The id of the connection an alias means for a workspace: its own, else the platform's; null for none. */
@FunctionalInterface
public interface ConnectionIdResolver {

    Long resolve(Long tenantId, String alias);
}
