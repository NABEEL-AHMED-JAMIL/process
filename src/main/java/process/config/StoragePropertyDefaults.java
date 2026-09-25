package process.config;

/**
 * The one spelling of each platform bucket property, so their readers cannot disagree about it.
 *
 * Every reader of a platform bucket has to name the same bucket. The avatar bucket's readers once
 * disagreed -- three took the property verbatim while the bootstrap trimmed it and substituted a
 * default for a blank one, so a value with a trailing space produced a connection the uploader never
 * asked for, and every upload failed with "Unknown bucket". The avatar bucket left process with
 * Identity's endpoints (MIG-108); the configuration bucket below keeps the rule.
 *
 * Normalising in the annotation rather than in each constructor keeps the rule in one place and
 * out of every body that would otherwise have to remember it. Security fails closed either way -- the
 * guard recognises the created row as platform-owned through its null tenant, whatever it is
 * called -- so this is a configuration trap being closed, not a hole.
 *
 * @author Nabeel Ahmed
 */
public final class StoragePropertyDefaults {

    /**
     * The platform's configuration bucket -- Kafka key material under kafka-secrets/ -- normalised
     * the same way and for the same reason: KafkaSecretServiceImpl writes to it, the browser
     * guard refuses it by name, the alias is reserved by it, and the bootstrap creates it.
     */
    public static final String CONFIG_BUCKET =
        "#{'${app.config.bucket:etl-config}'.trim().isEmpty() ? 'etl-config' "
        + ": '${app.config.bucket:etl-config}'.trim()}";

    private StoragePropertyDefaults() {}

}
