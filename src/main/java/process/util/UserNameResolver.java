package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import process.directory.UserDirectory;
import process.identity.IdentityPort;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.data.repository.CrudRepository;
import process.model.dto.AuditNamed;
import process.model.pojo.Audited;

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

    private final IdentityPort identity;
    private final UserDirectory directory;

    /** Names come from Identity, through the port (MIG-93): nothing here reads app_user. */
    private static final Logger logger = LoggerFactory.getLogger(UserNameResolver.class);

    /** Identity only, with no local directory: for tests of the code that calls this. */
    public UserNameResolver(IdentityPort identity) {
        this(identity, null);
    }

    /**
     * MIG-153: user_directory first -- Core's projection of Identity's people, kept by its events -- and
     * Identity only for the ids the directory does not hold, with the answers written back.
     */
    @Autowired
    public UserNameResolver(IdentityPort identity, UserDirectory directory) {
        this.identity = identity;
        this.directory = directory;
    }

    /**
     * Display names for the given ids. Ids that no longer match a user are simply absent, so a
     * caller falls back to showing nothing rather than a dangling number.
     */
    public Map<Long, String> namesFor(Collection<Long> userIds) {
        Set<Long> wanted = userIds == null ? new HashSet<>() : userIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return new HashMap<>();
        }
        // Across tenants on purpose: the author of a tenant's row may be a platform admin, and the
        // tenant filter would otherwise blank their name (MIG-13).
        Map<Long, String> names = new HashMap<>();
        Set<Long> missing = new HashSet<>(wanted);
        if (this.directory != null) {
            try {
                // One query for the whole list, however long (MIG-153).
                for (UserDirectory.Entry entry : this.directory.find(wanted).values()) {
                    names.put(entry.getAppUserId(), entry.getDisplayName());
                    missing.remove(entry.getAppUserId());
                }
            } catch (DataAccessException unreadable) {
                logger.warn("The user directory could not be read; asking Identity for {} name(s): {}", wanted.size(),
                    unreadable.getMessage());
            }
        }
        if (missing.isEmpty()) {
            return names;
        }
        // At most one call, for what the directory does not hold, stamped with when it was asked.
        Instant asked = Instant.now();
        Map<Long, IdentityPort.Person> people;
        try {
            people = this.identity.people(missing);
        } catch (IdentityPort.Unavailable unavailable) {
            // Names are for reading, never for deciding: the list is served without them (MIG-107).
            logger.warn("Names for {} user(s) are left out: {}", missing.size(), unavailable.getMessage());
            return names;
        }
        List<UserDirectory.Entry> learned = new ArrayList<>();
        for (IdentityPort.Person person : people.values()) {
            names.put(person.getAppUserId(), person.getDisplayName());
            learned.add(UserDirectory.Entry.of(person, asked));
        }
        if (this.directory != null) {
            this.directory.writeBack(learned);
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
    public void attachNames(Collection<? extends Audited> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        for (Audited row : rows) {
            if (row.getCreatedBy() != null) {
                ids.add(row.getCreatedBy());
            }
            if (row.getUpdatedBy() != null) {
                ids.add(row.getUpdatedBy());
            }
        }
        Map<Long, String> names = namesFor(ids);
        for (Audited row : rows) {
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
    public <E extends Audited> void attachToDtos(
        List<? extends AuditNamed> dtos,
        CrudRepository<E, Long> repository,
        Function<E, Long> idOf) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Set<Long> ids = dtos.stream().map(AuditNamed::auditKey)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        List<E> entities = new ArrayList<>();
        repository.findAllById(ids).forEach(entities::add);
        attachNames(entities);
        Map<Long, E> byId = new HashMap<>();
        for (E entity : entities) {
            byId.put(idOf.apply(entity), entity);
        }
        for (AuditNamed dto : dtos) {
            E entity = byId.get(dto.auditKey());
            if (entity != null) {
                dto.setCreatedByName(entity.getCreatedByName());
                dto.setUpdatedByName(entity.getUpdatedByName());
                dto.setCreatedBy(entity.getCreatedBy());
            }
        }
    }

    /** One row, for the single-record reads. */
    public void attachNames(Audited row) {
        if (row != null) {
            attachNames(Collections.singletonList(row));
        }
    }

    /** One id, for the single-record reads where a batch would be overkill. */
    public String nameFor(Long userId) {
        if (userId == null) {
            return null;
        }
        if (this.directory != null) {
            return this.namesFor(Collections.singletonList(userId)).get(userId);
        }
        try {
            return this.identity.person(userId).map(IdentityPort.Person::getDisplayName).orElse(null);
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("The name of user {} is left out: {}", userId, unavailable.getMessage());
            return null;
        }
    }

}
