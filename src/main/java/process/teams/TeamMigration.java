package process.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * F4 (MIG-401, owner T-Q1, 2026-10-21): groups became teams. A "group" was an access profile id; each became a team of the
 * same people, made once by Identity (/internal/identity/teams/fromProfiles). Every minute until nothing names a group
 * any more, one replica at a time (ShedLock), this finds each workspace's group ids -- Approval requests (run_approval
 * reviewers_kind 'group'), pipeline definitions ({kind: "group", value} in settings.review.reviewers and an Approval
 * step's reviewers, every version) and alert rules' recipient_groups -- asks Identity for the team each became, and
 * rewrites them to that team in that workspace (RowSecurity.forTenant), so every review, approval and alert still goes to
 * the same people. A workspace Identity cannot answer for waits for the next tick. Once a tick finds nothing it stops
 * looking; V224 then takes 'group' and recipient_groups out on the next start.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class TeamMigration {

    static final String GROUP = "group";
    static final String TEAM = "team";

    private static final Logger logger = LoggerFactory.getLogger(TeamMigration.class);

    private final JdbcTemplate jdbc;
    private final IdentityPort identity;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean done;

    public TeamMigration(JdbcTemplate jdbc, IdentityPort identity) {
        this.jdbc = jdbc;
        this.identity = identity;
    }

    @Scheduled(initialDelayString = "${process.team-migration.initial-delay-ms:15000}", fixedDelayString = "${process.team-migration.sweep-ms:60000}")
    @SchedulerLock(name = "teamMigration", lockAtMostFor = "5M")
    public void sweep() {
        if (this.done) {
            return;
        }
        Map<Long, Set<Long>> groups = RowSecurity.acrossTenants("groups became teams (F4, T-Q1): every workspace's group ids, to"
            + " rewrite each to the team it became in its own workspace", this::groupsByWorkspace);
        if (groups.isEmpty()) {
            this.done = true;
            return;
        }
        for (Map.Entry<Long, Set<Long>> workspace : groups.entrySet()) {
            long tenant = workspace.getKey();
            Map<Long, Long> teams;
            try {
                teams = this.identity.teamsOfGroups(tenant, workspace.getValue());
            } catch (IdentityPort.Unavailable unavailable) {
                logger.warn("Identity could not say which teams workspace {}'s groups became; they wait for the next tick: {}", tenant,
                    unavailable.getMessage());
                continue;
            }
            if (!teams.keySet().containsAll(workspace.getValue())) {
                logger.warn("Identity did not say which teams workspace {}'s groups {} became; they wait for the next tick.", tenant,
                    workspace.getValue());
                continue;
            }
            try {
                int[] changed = RowSecurity.forTenant(tenant, () -> this.rewrite(tenant, teams));
                logger.info("Groups became teams in workspace {}: {} approval(s), {} pipeline version(s), {} alert rule(s) now name teams {}.",
                    tenant, changed[0], changed[1], changed[2], teams);
            } catch (RuntimeException failed) {
                logger.error("Could not move workspace {}'s groups to teams: {}", tenant, failed.getMessage(), failed);
            }
        }
    }

    /** Whether alert_rule still has recipient_groups (V224 drops it). */
    private boolean alertGroupsColumn() {
        Integer found = this.jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = 'alert_rule' AND column_name = 'recipient_groups'", Integer.class);
        return found != null && found > 0;
    }

    /** Workspace -> the group ids its approvals, pipeline definitions and alert rules name. */
    Map<Long, Set<Long>> groupsByWorkspace() {
        Map<Long, Set<Long>> out = new TreeMap<>();
        this.jdbc.query("SELECT DISTINCT tenant_id, reviewers_value FROM run_approval WHERE reviewers_kind = 'group'", rs -> {
            Long id = idOf(rs.getString(2));
            if (id != null) {
                out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(id);
            }
        });
        this.jdbc.query("SELECT tenant_id, definition::text FROM pipeline_definition WHERE definition::text LIKE '%\"group\"%'", rs -> {
            Set<Long> found = new LinkedHashSet<>();
            collect(this.read(rs.getString(2)), found);
            if (!found.isEmpty()) {
                out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).addAll(found);
            }
        });
        if (this.alertGroupsColumn()) {
            this.jdbc.query("SELECT tenant_id, recipient_groups FROM alert_rule WHERE recipient_groups IS NOT NULL AND tenant_id IS NOT NULL", rs -> {
                Set<Long> ids = out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>());
                for (String word : rs.getString(2).split(",")) {
                    Long id = idOf(word);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            });
        }
        return out;
    }

    /** {approvals, pipeline versions, alert rules} rewritten in the caller's workspace. */
    int[] rewrite(long tenant, Map<Long, Long> teams) {
        int approvals = 0;
        for (Map.Entry<Long, Long> team : teams.entrySet()) {
            approvals += this.jdbc.update("UPDATE run_approval SET reviewers_kind = 'team', reviewers_value = ?, date_updated = now() "
                + "WHERE tenant_id = ? AND reviewers_kind = 'group' AND reviewers_value = ?", String.valueOf(team.getValue()), tenant,
                String.valueOf(team.getKey()));
        }
        List<Object[]> versions = new ArrayList<>();
        this.jdbc.query("SELECT pipeline_definition_id, definition::text FROM pipeline_definition WHERE tenant_id = ? "
            + "AND definition::text LIKE '%\"group\"%'", rs -> {
                JsonNode node = this.read(rs.getString(2));
                if (node != null && replace(node, teams)) {
                    versions.add(new Object[] {node.toString(), rs.getLong(1)});
                }
            }, tenant);
        for (Object[] row : versions) {
            this.jdbc.update("UPDATE pipeline_definition SET definition = ?::json WHERE pipeline_definition_id = ?", row);
        }
        int rules = 0;
        if (this.alertGroupsColumn()) {
            List<Object[]> moved = new ArrayList<>();
            this.jdbc.query("SELECT alert_rule_id, recipient_groups, recipient_teams FROM alert_rule WHERE tenant_id = ? AND recipient_groups IS NOT NULL",
                rs -> {
                    Set<Long> now = new LinkedHashSet<>();
                    for (String word : (rs.getString(3) == null ? "" : rs.getString(3)).split(",")) {
                        Long id = idOf(word);
                        if (id != null) {
                            now.add(id);
                        }
                    }
                    for (String word : rs.getString(2).split(",")) {
                        Long id = idOf(word);
                        if (id != null && teams.containsKey(id)) {
                            now.add(teams.get(id));
                        }
                    }
                    moved.add(new Object[] {now.isEmpty() ? null : now.stream().map(String::valueOf).collect(Collectors.joining(",")), rs.getLong(1)});
                }, tenant);
            for (Object[] row : moved) {
                rules += this.jdbc.update("UPDATE alert_rule SET recipient_teams = ?, recipient_groups = NULL, date_updated = now() "
                    + "WHERE alert_rule_id = ?", row);
            }
        }
        return new int[] {approvals, versions.size(), rules};
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : this.json.readTree(text);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static Long idOf(String text) {
        String word = text == null ? "" : text.trim();
        return word.matches("\\d{1,18}") ? Long.valueOf(word) : null;
    }

    /** The values of every {kind: "group", value} in the tree. */
    static void collect(JsonNode node, Set<Long> found) {
        if (node == null) {
            return;
        }
        if (node.isObject() && GROUP.equals(node.path("kind").asText(null)) && node.hasNonNull("value")) {
            Long id = idOf(node.get("value").asText());
            if (id != null) {
                found.add(id);
            }
        }
        node.forEach(child -> collect(child, found));
    }

    /** Each {kind: "group", value} becomes {kind: "team", value: its team}, a number staying a number; true when any did. */
    static boolean replace(JsonNode node, Map<Long, Long> teams) {
        boolean changed = false;
        if (node.isObject() && GROUP.equals(node.path("kind").asText(null)) && node.hasNonNull("value")) {
            Long id = idOf(node.get("value").asText());
            if (id != null && teams.containsKey(id)) {
                ObjectNode who = (ObjectNode) node;
                if (who.get("value").isNumber()) {
                    who.put("value", teams.get(id));
                } else {
                    who.put("value", String.valueOf(teams.get(id)));
                }
                who.put("kind", TEAM);
                changed = true;
            }
        }
        for (JsonNode child : node) {
            changed |= replace(child, teams);
        }
        return changed;
    }
}
