package process.util;

import org.springframework.stereotype.Component;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the app-user ids stored in created_by / updated_by into something a person can read.
 *
 * Those columns have been filled in all along -- five services write them on save -- but no DTO
 * carried them, so every screen showed work with no author. Resolving them one at a time while
 * mapping a list would mean a query per row, so callers hand over every id at once and get a
 * lookup back.
 *
 * @author Nabeel Ahmed
 */
@Component
public class UserNameResolver {

    private final AppUserRepository appUserRepository;

    public UserNameResolver(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * Display names for the given ids. Ids that no longer match a user are simply absent, so a
     * caller falls back to showing nothing rather than a dangling number.
     */
    public Map<Long, String> namesFor(Collection<Long> userIds) {
        Set<Long> wanted = userIds == null ? new HashSet<>() : userIds.stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return new HashMap<>();
        }
        List<AppUser> found = this.appUserRepository.findAllById(wanted);
        Map<Long, String> names = new HashMap<>();
        for (AppUser user : found) {
            names.put(user.getAppUserId(), displayName(user));
        }
        return names;
    }

    /**
     * Fills in the readable names on a whole list in one lookup.
     *
     * The obvious version -- resolve each row as it is mapped -- is a query per row, which on a
     * few hundred jobs is a few hundred round trips. Both columns are gathered first, so a list
     * costs exactly one query no matter how long it is.
     */
    public void attachNames(Collection<? extends process.model.pojo.Audited> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (process.model.pojo.Audited row : rows) {
            if (row.getCreatedBy() != null) {
                ids.add(row.getCreatedBy());
            }
            if (row.getUpdatedBy() != null) {
                ids.add(row.getUpdatedBy());
            }
        }
        Map<Long, String> names = namesFor(ids);
        for (process.model.pojo.Audited row : rows) {
            row.setCreatedByName(names.get(row.getCreatedBy()));
            row.setUpdatedByName(names.get(row.getUpdatedBy()));
        }
    }

    /**
     * Attaches names to DTOs whose entities have to be fetched separately.
     *
     * Two queries for a whole page -- one for the rows, one for the people -- regardless of how
     * many rows there are. A DTO whose entity has since been deleted simply keeps no name.
     */
    public <E extends process.model.pojo.Audited> void attachToDtos(
        java.util.List<? extends process.model.dto.AuditNamed> dtos,
        org.springframework.data.repository.CrudRepository<E, Long> repository,
        java.util.function.Function<E, Long> idOf) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Set<Long> ids = dtos.stream().map(process.model.dto.AuditNamed::auditKey)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        List<E> entities = new java.util.ArrayList<>();
        repository.findAllById(ids).forEach(entities::add);
        attachNames(entities);
        Map<Long, E> byId = new HashMap<>();
        for (E entity : entities) {
            byId.put(idOf.apply(entity), entity);
        }
        for (process.model.dto.AuditNamed dto : dtos) {
            E entity = byId.get(dto.auditKey());
            if (entity != null) {
                dto.setCreatedByName(entity.getCreatedByName());
                dto.setUpdatedByName(entity.getUpdatedByName());
                dto.setCreatedBy(entity.getCreatedBy());
            }
        }
    }

    /** One row, for the single-record reads. */
    public void attachNames(process.model.pojo.Audited row) {
        if (row != null) {
            attachNames(java.util.Collections.singletonList(row));
        }
    }

    /** One id, for the single-record reads where a batch would be overkill. */
    public String nameFor(Long userId) {
        if (userId == null) {
            return null;
        }
        return this.appUserRepository.findById(userId).map(UserNameResolver::displayName).orElse(null);
    }

    /** Full name where there is one; the username is the fallback, since it is never blank. */
    private static String displayName(AppUser user) {
        String fullName = user.getFullName();
        return (fullName == null || fullName.trim().isEmpty()) ? user.getUsername() : fullName.trim();
    }
}
