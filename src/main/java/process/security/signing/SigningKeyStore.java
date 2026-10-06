package process.security.signing;

import java.util.List;
import java.util.Optional;

/**
 * Where Identity keeps its signing keys (MIG-92): its own database, never an environment file.
 *
 * @author Nabeel Ahmed
 */
public interface SigningKeyStore {

    /** The one key new tokens are signed with, if there is one yet. */
    Optional<StoredSigningKey> active();

    /** Every key still published: the active one and those retired within the longest token life. */
    List<StoredSigningKey> published();

    /**
     * Stores this as the active key unless another instance got there first. At most one key is ever
     * active: the database refuses a second, so two instances starting together agree on one.
     *
     * @return whether this one was stored
     */
    boolean insertActiveIfNone(StoredSigningKey key);

    /** The active key stops signing; it is still published until the tokens it signed have expired. */
    void retire(String kid);
}
