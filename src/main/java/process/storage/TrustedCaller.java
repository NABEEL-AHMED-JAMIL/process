package process.storage;

/**
 * Who may use Storage's trusted operations (MIG-52, MIG-65) -- the whole list. Each builds the bucket
 * and key itself, from a row it has already established belongs to whoever is asking, or from its own
 * configuration; none takes either from a request. Adding a caller is a decision recorded here and in
 * TrustedStorageBoundaryTest.
 */
public enum TrustedCaller {

    /** Reads a Kafka profile's key material off the profile row, on dispatch threads with no principal. */
    KAFKA_TEMPLATE_PROVIDER,
    /** Stores and reads Kafka certificates under paths it builds for the caller's own profile. */
    KAFKA_SECRETS,
    /** Reads a user's picture from the avatar fields of that user's own row. */
    IDENTITY_AVATAR,
    /** Keeps invoices, receipts and slips under keys it builds from the document row (DEF-021: unmetered). */
    BILLING_DOCUMENTS
}
