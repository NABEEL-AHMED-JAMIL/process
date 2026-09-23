package process.notifications;

import org.barco.notifications.contract.MailRequested;

/**
 * The mails whose wording Core decides, in the contract's shape. Moved here unchanged from the
 * typed send* methods of EmailMessagesFactory, which built these same body maps.
 *
 * A welcome mail's temporary password is NOT in the body: it travels in {@link MailExtras} and
 * Notifications adds it at render time (contract rule: no credential in an event).
 */
public final class StandardMails {

    /** Marks an attachment that still travels as bytes in MailExtras, until MIG-22 stages it. */
    static final String IN_PROCESS_BUCKET = "in-process";

    private StandardMails() {
    }

    public static MailRequested fileShare(String recipient, String senderName, String itemName, String itemType,
        boolean zipped, String sizeLabel, String message, String attachmentFilename, String attachmentContentType,
        long attachmentSize) {
        return new MailRequested().setTemplate(MailRequested.Template.FILE_SHARE).setRecipient(recipient)
            .setSubject(senderName + " shared \"" + itemName + "\" with you")
            .put("sender_name", senderName)
            .put("item_name", itemName)
            .put("item_type", itemType)
            .put("item_label", "Folder".equals(itemType) ? "a folder" : "Selection".equals(itemType) ? "a selection" : "a file")
            .put("size_label", sizeLabel)
            .put("message", message)
            .put("attachment_note", zipped ? "It's attached below as a ZIP file." : "It's attached below.")
            .setAttachmentRef(new MailRequested.AttachmentRef().setBucket(IN_PROCESS_BUCKET)
                .setKey(IN_PROCESS_BUCKET + "/" + attachmentFilename).setFilename(attachmentFilename)
                .setContentType(attachmentContentType).setSizeBytes(attachmentSize));
    }

    public static MailRequested tenantWelcome(String recipient, String contactName, String organisationName,
        String username, String signInUrl) {
        return new MailRequested().setTemplate(MailRequested.Template.TENANT_WELCOME).setRecipient(recipient)
            .setSubject("Your " + organisationName + " workspace is ready")
            .put("contact_name", contactName)
            .put("organisation_name", organisationName)
            .put("username", username)
            .put("sign_in_url", signInUrl);
    }

    public static MailRequested userWelcome(String recipient, String fullName, String organisationName,
        String username, String roleLabel, String createdByName, String signInUrl) {
        return new MailRequested().setTemplate(MailRequested.Template.USER_WELCOME).setRecipient(recipient)
            .setSubject("Your ETL Console account")
            .put("full_name", fullName)
            .put("organisation_name", organisationName)
            .put("username", username)
            .put("role_label", roleLabel)
            .put("created_by_name", createdByName)
            .put("sign_in_url", signInUrl);
    }
}
