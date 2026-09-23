package process.storage;

import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Which storage connection an alias means (MIG-53). An alias is unique within a workspace, not across
 * the platform, so a name alone is not an answer: every lookup here says whose name it is.
 *
 * With platform-wide aliases, the name a caller sends as "bucket" could resolve through a connection
 * that is not theirs -- the recorded alias/bucket confusion -- and any endpoint taking an alias let a
 * tenant name another tenant's connection. Platform connections (no tenant) own their names outright:
 * no workspace may take one, and the platform may not take a name a workspace uses, so a platform
 * name can never be shadowed.
 */
public class StorageConnectionLookup {

    private final StorageConnectionRepository connections;

    public StorageConnectionLookup(StorageConnectionRepository connections) {
        this.connections = connections;
    }

    /** The platform's connection by this name, of any status. */
    public Optional<StorageConnection> platform(String alias) {
        return this.connections.findByTenantIdIsNullAndAlias(alias);
    }

    /** A workspace's own connection by this name, else the platform's; with no tenant, the platform's. */
    public Optional<StorageConnection> ownOrPlatform(Long tenantId, String alias) {
        if (tenantId != null) {
            Optional<StorageConnection> own = this.connections.findByTenantIdAndAlias(tenantId, alias);
            if (own.isPresent()) {
                return own;
            }
        }
        return this.platform(alias);
    }

    /**
     * What the signed-in caller means by this name. A workspace user: their own, else the platform's
     * (the platform-bucket guard decides whether they may have it). A platform admin, who has no
     * workspace: the platform's, else the one workspace's that uses the name -- and when several do,
     * the name is refused as ambiguous rather than guessed at.
     */
    public Optional<StorageConnection> forCaller(String alias) {
        if (!TenantContext.isPlatformAdmin()) {
            Long tenantId = TenantContext.getTenantId();
            return tenantId == null ? this.platform(alias) : this.ownOrPlatform(tenantId, alias);
        }
        Optional<StorageConnection> platform = this.platform(alias);
        if (platform.isPresent()) {
            return platform;
        }
        List<StorageConnection> workspaces = this.connections.findAllByAlias(alias).stream()
            .filter(c -> c.getTenantId() != null).collect(Collectors.toList());
        if (workspaces.size() > 1) {
            throw new IllegalArgumentException("'" + alias + "' names a connection in more than one workspace; "
                + "open it from that workspace instead.");
        }
        return workspaces.stream().findFirst();
    }

    /**
     * Whether a connection may not take this name. A workspace (tenantId set): its own name twice, or
     * any platform name. The platform (tenantId null): any name already in use anywhere. exceptId is
     * the row being renamed, which may keep its own name.
     */
    public boolean aliasUnavailable(Long tenantId, String alias, Long exceptId) {
        for (StorageConnection holder : this.connections.findAllByAlias(alias)) {
            if (exceptId != null && exceptId.equals(holder.getStorageConnectionId())) {
                continue;
            }
            if (tenantId == null || holder.getTenantId() == null || tenantId.equals(holder.getTenantId())) {
                return true;
            }
        }
        return false;
    }
}
