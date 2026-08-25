package process.model.pojo;

import process.security.TenantContext;

import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

/**
 * Stamps the acting user onto anything that is saved.
 *
 * Done here rather than in each service because there are dozens of save paths and the ones
 * that already wrote created_by by hand only covered five entities -- every other screen showed
 * work with no author. A listener cannot be forgotten by a new endpoint.
 *
 * Nothing is stamped when there is no acting user. Scheduled work, Kafka callbacks and the
 * public workspace-request form all run with no one signed in, and a row those touch honestly
 * has no human author; writing an id there would be a lie that outlives the request.
 */
public class AuditListener {

    @PrePersist
    public void onCreate(Object entity) {
        if (!(entity instanceof Audited)) {
            return;
        }
        Long actor = TenantContext.getAppUserId();
        if (actor == null) {
            return;
        }
        Audited audited = (Audited) entity;
        // Only when it is still blank: an approved workspace request names the new administrator
        // as the author of their own account, and the platform admin who approved it should not
        // overwrite that.
        if (audited.getCreatedBy() == null) {
            audited.setCreatedBy(actor);
        }
    }

    @PreUpdate
    public void onUpdate(Object entity) {
        if (!(entity instanceof Audited)) {
            return;
        }
        Long actor = TenantContext.getAppUserId();
        if (actor == null) {
            return;
        }
        ((Audited) entity).setUpdatedBy(actor);
    }
}
