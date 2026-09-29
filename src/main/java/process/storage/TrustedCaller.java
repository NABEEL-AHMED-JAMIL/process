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
    /**
     * A pipeline's bucket steps (MIG-231: Read S3, Read CSV/JSON/Parquet, Upload to bucket) -- list, read and upload in
     * the run's own workspace, whose bucket alias must resolve to that workspace's own connection. The step engine
     * names the workspace from the run it runs, never from a request. storage-service must accept it first (its own
     * TrustedCaller, fenced as INTEGRATION_SOURCES is, with upload allowed); until then the tasks are unavailable
     * (process.pipeline.storage.trusted-caller).
     */
    CORE_PIPELINES
    // BILLING_DOCUMENTS left with Billing (MIG-88/89): billing-service is storage-service's caller now.
    // IDENTITY_AVATAR left with Identity's endpoints (MIG-108): identity-service reads its pictures itself.
}
