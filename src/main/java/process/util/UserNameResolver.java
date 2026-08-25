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
