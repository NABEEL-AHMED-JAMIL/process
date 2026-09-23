package process.storage.remote;

import process.model.enums.Status;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * storage-service for tests that need connections to exist (MIG-70): rows held in memory and vended
 * by Storage's rules -- Active, and the caller's own, a platform administrator owning every one (the
 * rules themselves are tested in storage-service's ConnectionDirectoryTest). The rows are handed back
 * as given, secrets sealed however the test sealed them.
 */
public class FakeStorage extends RemoteStorageDirectory {

    private final List<StorageConnection> rows = new ArrayList<>();

    public FakeStorage() {
        super(null, null);
    }

    public StorageConnection add(StorageConnection row) {
        this.rows.add(row);
        return row;
    }

    public void clear() {
        this.rows.clear();
    }

    @Override
    public Optional<StorageConnection> vendForCaller(String alias) {
        Optional<StorageConnection> found;
        if (TenantContext.isPlatformAdmin()) {
            Optional<StorageConnection> platform = this.named(alias).stream().filter(r -> r.getTenantId() == null).findFirst();
            List<StorageConnection> workspaces = this.named(alias).stream().filter(r -> r.getTenantId() != null).collect(Collectors.toList());
            found = platform.isPresent() ? platform : workspaces.size() == 1 ? Optional.of(workspaces.get(0)) : Optional.empty();
        } else {
            Long caller = TenantContext.getTenantId();
            found = caller == null ? Optional.empty()
                : this.named(alias).stream().filter(r -> caller.equals(r.getTenantId())).findFirst();
        }
        return found.filter(r -> r.getStatus() == Status.Active);
    }

    @Override
    public List<StorageConnection> byAlias(String alias) {
        return this.named(alias).stream().filter(r -> r.getStatus() != Status.Delete).collect(Collectors.toList());
    }

    @Override
    public List<StorageConnection> workspace(Long tenantId) {
        return this.rows.stream().filter(r -> r.getStatus() != Status.Delete)
            .filter(r -> tenantId == null ? r.getTenantId() == null : tenantId.equals(r.getTenantId())).collect(Collectors.toList());
    }

    @Override
    public Long resolve(Long tenantId, String alias) {
        return null;
    }

    private List<StorageConnection> named(String alias) {
        return this.rows.stream().filter(r -> alias.equals(r.getAlias())).collect(Collectors.toList());
    }
}
