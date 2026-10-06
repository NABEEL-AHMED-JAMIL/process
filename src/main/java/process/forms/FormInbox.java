package process.forms;

/**
 * Where a submission's file goes (Wave 5 Forms lite): the workspace's inbox -- the storage connection an administrator
 * chose for it in storage-service (MIG-239) -- asked as the signed-in person who submits, so only their own workspace's
 * inbox is ever named.
 */
public interface FormInbox {

    /** The inbox's connection alias, or why there is none to write to. */
    final class Location {
        public final String alias;
        public final String refusal;

        private Location(String alias, String refusal) {
            this.alias = alias;
            this.refusal = refusal;
        }

        public static Location at(String alias) {
            return new Location(alias, null);
        }

        public static Location none(String refusal) {
            return new Location(null, refusal);
        }
    }

    Location locate();
}
