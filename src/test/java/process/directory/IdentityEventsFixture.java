package process.directory;

import java.util.UUID;

/**
 * Identity's lifecycle events exactly as identity-service's V3.0 triggers write them (MIG-166): the
 * PlatformEvent envelope, an Instant with microseconds and a 'Z', a person as five columns and a timestamp,
 * a workspace as four and a timestamp. A null tenant is a JSON null (a platform administrator).
 */
final class IdentityEventsFixture {

    private IdentityEventsFixture() {
    }

    static String user(String type, long appUserId, Long tenantId, String username, String fullName, String status, String at) {
        String tenant = tenantId == null ? "null" : String.valueOf(tenantId);
        return "{\"payload\": {\"status\": \"" + status + "\", \"tenantId\": " + tenant + ", \"fullName\": "
            + (fullName == null ? "null" : "\"" + fullName + "\"") + ", \"username\": \"" + username + "\", \"appUserId\": "
            + appUserId + ", \"updatedAt\": \"" + at + "\"}, \"eventId\": \"" + UUID.randomUUID() + "\", \"producer\": "
            + "\"identity-service\", \"tenantId\": " + tenant + ", \"eventType\": \"" + type + "\", \"occurredAt\": \"" + at + "\"}";
    }

    static String tenant(String type, long tenantId, String code, String status, String at) {
        return "{\"payload\": {\"status\": \"" + status + "\", \"tenantId\": " + tenantId + ", \"tenantCode\": \"" + code
            + "\", \"tenantName\": \"Workspace " + code + "\", \"updatedAt\": \"" + at + "\"}, \"eventId\": \"" + UUID.randomUUID()
            + "\", \"producer\": \"identity-service\", \"tenantId\": " + tenantId + ", \"eventType\": \"" + type
            + "\", \"occurredAt\": \"" + at + "\"}";
    }
}
