package process.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import process.security.TenantContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Workspace names for the rows of a list (MIG-296): a platform administrator's jobs, tasks and hour
 * drill-down mix every workspace and said nowhere which row was whose.
 *
 * Asked of Identity, the source of truth -- Core's own tenant table stopped following Identity long
 * ago -- in one call per list, and only for a platform administrator: anyone else sees their own
 * workspace alone, so a name would say nothing. The names are a courtesy: when Identity cannot be
 * asked the list still loads, and the console writes "Workspace #id".
 *
 * @author Nabeel Ahmed
 */
public final class WorkspaceNames {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceNames.class);

    private WorkspaceNames() {}

    /** tenant id -> workspace name for these rows' workspaces; empty unless the caller is a platform administrator. */
    public static Map<Long, String> forCaller(IdentityPort identity, Collection<Long> tenantIds) {
        if (identity == null || !TenantContext.isPlatformAdmin()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = tenantIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return identity.workspaces(ids).stream()
                .filter(workspace -> workspace.getTenantId() != null && workspace.getName() != null)
                .collect(Collectors.toMap(IdentityPort.Workspace::getTenantId, IdentityPort.Workspace::getName, (a, b) -> a));
        } catch (RuntimeException unreachable) {
            logger.warn("Could not fetch the names of {} workspaces for a list: {}", ids.size(), unreachable.getMessage());
            return Collections.emptyMap();
        }
    }
}
