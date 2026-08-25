package process.model.pojo;

/**
 * An entity that records who made it and who last changed it.
 *
 * Implemented rather than inherited: these entities already have their own shape and their own
 * id fields, and a mapped superclass would have meant rewriting all of them. What this buys is
 * that {@link AuditListener} can stamp any of them without knowing which one it holds.
 */
public interface Audited {

    Long getCreatedBy();

    void setCreatedBy(Long createdBy);

    Long getUpdatedBy();

    void setUpdatedBy(Long updatedBy);

    /**
     * The readable name, filled in on the way out rather than stored.
     *
     * Kept off the table on purpose: a person can change their name, and a copy frozen at save
     * time would slowly drift away from who they are now.
     */
    String getCreatedByName();

    void setCreatedByName(String createdByName);

    String getUpdatedByName();

    void setUpdatedByName(String updatedByName);
}
