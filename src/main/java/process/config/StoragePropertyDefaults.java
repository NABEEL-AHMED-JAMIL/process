package process.config;

/**
 * The one spelling of the avatar bucket property, so its four readers cannot disagree about it.
 *
 * StorageBrowserServiceImpl (which guards the bucket), StorageConnectionServiceImpl (which
 * reserves the alias), AppUserServiceImpl (which writes the pictures) and StorageConnectionBootstrap
 * (which creates the connection) all have to name the same bucket. Three of them took the property
 * verbatim while the bootstrap trimmed it and substituted a default for a blank one, so a value
 * with a trailing space -- or an AVATAR_BUCKET set to "" -- produced a connection called
 * "etl-avatar" that the uploader never asked for: every avatar upload failed with "Unknown bucket"
 * while the storage screen showed a connection that looked correctly configured.
 *
 * Normalising in the annotation rather than in each constructor keeps the rule in one place and
 * out of four bodies that would each have to remember it. Security fails closed either way -- the
 * guard recognises the created row as platform-owned through its null tenant, whatever it is
 * called -- so this is a configuration trap being closed, not a hole.
 *
 * @author Nabeel Ahmed
 */
public final class StoragePropertyDefaults {

    /**
     * Trimmed, and falling back to etl-avatar when what is configured is blank. Written as a
     * constant because an annotation argument has to be one; the placeholder inside is resolved
     * before the expression around it is evaluated.
     */
    public static final String AVATAR_BUCKET =
        "#{'${app.avatar.bucket:etl-avatar}'.trim().isEmpty() ? 'etl-avatar' "
        + ": '${app.avatar.bucket:etl-avatar}'.trim()}";

    private StoragePropertyDefaults() {}

}
