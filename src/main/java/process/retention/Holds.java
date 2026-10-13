package process.retention;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MIG-354: the legal holds active in a workspace, as a pass reads them once at its start. A workspace hold stops every
 * class; pipeline and run holds keep their runs (and what names them) out of every batch of a class that knows its runs.
 */
public final class Holds {

    public static final Holds NONE = new Holds(false, Collections.emptySet(), Collections.emptySet());

    public final boolean workspace;
    public final Set<Long> jobIds;
    public final Set<Long> runIds;

    public Holds(boolean workspace, Set<Long> jobIds, Set<Long> runIds) {
        this.workspace = workspace;
        this.jobIds = Collections.unmodifiableSet(new LinkedHashSet<>(jobIds));
        this.runIds = Collections.unmodifiableSet(new LinkedHashSet<>(runIds));
    }

    public boolean any() {
        return this.workspace || !this.jobIds.isEmpty() || !this.runIds.isEmpty();
    }

    /** As a bigint[] argument: never empty, so "<> ALL" reads the same with no holds (0 is no id; a missing id reads as -1). */
    static Long[] array(Set<Long> ids) {
        return ids.isEmpty() ? new Long[]{0L} : ids.toArray(new Long[0]);
    }

    Long[] jobArray() {
        return array(this.jobIds);
    }

    Long[] runArray() {
        return array(this.runIds);
    }
}
