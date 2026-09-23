package process.notifications;

/**
 * What a mail needs that must not travel in its event, while Notifications is still in this process.
 *
 * Neither field is ever serialised. The attachment's bytes become a staged object and an
 * AttachmentRef when Notifications leaves (MIG-22); the secret becomes a secretRef that Notifications
 * redeems once with Identity (contract rule: no credential in an event, ever).
 */
public final class MailExtras {

    public static final MailExtras NONE = new MailExtras(null, null);

    private final byte[] attachment;
    private final String secret;

    private MailExtras(byte[] attachment, String secret) {
        this.attachment = attachment;
        this.secret = secret;
    }

    public static MailExtras attachment(byte[] bytes) {
        return new MailExtras(bytes, null);
    }

    public static MailExtras secret(String secret) {
        return new MailExtras(null, secret);
    }

    public byte[] getAttachment() {
        return this.attachment;
    }

    public String getSecret() {
        return this.secret;
    }

    @Override
    public String toString() {
        // A mail failure logs its arguments; this one must never print the secret.
        return "MailExtras{attachment=" + (this.attachment == null ? "none" : this.attachment.length + " bytes")
            + ", secret=" + (this.secret == null ? "none" : "***") + "}";
    }
}
