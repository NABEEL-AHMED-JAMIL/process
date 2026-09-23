package process.analytics;

/**
 * An analytics row that names a storage connection by alias, and carries that connection's id beside
 * it (MIG-53 part b). Stamped by AnalyticsConnectionStamp on every write.
 */
public interface ConnectionAliased {

    Long getTenantId();

    String getConnectionAlias();

    void setStorageConnectionId(Long storageConnectionId);

    /** A second connection (a join across two); null for rows that name one. */
    default String getSecondConnectionAlias() {
        return null;
    }

    default void setSecondStorageConnectionId(Long storageConnectionId) {
    }
}
