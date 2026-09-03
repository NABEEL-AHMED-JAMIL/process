package process.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Where a Kafka certificate, key or generated store lives in object storage.
 *
 * The layout is kafka-secrets/{appUserId}/{uuid}/{yyyy-MM-dd}/{filename}, and every part of it is
 * decided by the server. That is the point: the generic object endpoint takes a prefix from the
 * caller, which is fine for a folder of reports and quite wrong for private keys, because a caller
 * who chooses the path can also choose somebody else's. Here the caller supplies a filename and
 * nothing more.
 *
 * Each segment earns its place. The user id makes ownership readable from the key alone, so a
 * request can be authorised without a database round trip. The uuid separates one upload from the
 * next, so re-uploading a rotated certificate cannot overwrite the one a running connection is
 * still using -- the old profile keeps pointing at the old key until it is repointed. The date is
 * for the humans who will eventually have to work out what can be deleted.
 *
 * @author Nabeel Ahmed
 * */
public final class KafkaSecretPath {

    public static final String ROOT = "kafka-secrets";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * What an uploaded filename may contain once it has been cleaned up.
     *
     * Not a blacklist of the dangerous characters but a whitelist of the harmless ones, because a
     * key is concatenated into a path and the ways to escape one are more numerous than they look:
     * "..", a backslash on a Windows-backed store, an encoded slash, a NUL byte.
     */
    private static final String SAFE_FILENAME = "[^A-Za-z0-9._-]";

    private final Long appUserId;
    private final String uploadId;
    private final LocalDate uploadedOn;
    private final String fileName;

    private KafkaSecretPath(Long appUserId, String uploadId, LocalDate uploadedOn, String fileName) {
        this.appUserId = appUserId;
        this.uploadId = uploadId;
        this.uploadedOn = uploadedOn;
        this.fileName = fileName;
    }

    /**
     * A fresh location for one upload.
     *
     * The uuid is generated here rather than accepted from the caller, so that two uploads can
     * never be made to collide on purpose.
     */
    public static KafkaSecretPath newUpload(Long appUserId, String requestedFileName, LocalDate today) {
        if (appUserId == null) {
            throw new IllegalArgumentException("Kafka secrets cannot be stored without a signed-in user.");
        }
        return new KafkaSecretPath(appUserId, UUID.randomUUID().toString(),
            today == null ? LocalDate.now() : today, safeFileName(requestedFileName));
    }

    /** Another file in the same upload, so a certificate and the store built from it stay together. */
    public KafkaSecretPath sibling(String otherFileName) {
        return new KafkaSecretPath(this.appUserId, this.uploadId, this.uploadedOn, safeFileName(otherFileName));
    }

    /**
     * Reads a stored key back into its parts, or null when it is not one of ours.
     *
     * Returning null rather than throwing because the commonest caller is an authorisation check
     * asking "is this one of mine", and a key from somewhere else entirely is an ordinary no.
     */
    public static KafkaSecretPath parse(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        // Rejected before splitting: a traversal segment would otherwise be carried through into
        // the parsed form and the caller would authorise against a path that is not the one the
        // storage backend will resolve.
        if (trimmed.contains("..") || trimmed.contains("\\") || trimmed.contains("//")) {
            return null;
        }
        String[] parts = trimmed.split("/");
        if (parts.length != 5 || !ROOT.equals(parts[0])) {
            return null;
        }
        try {
            KafkaSecretPath parsed = new KafkaSecretPath(Long.parseLong(parts[1]), parts[2],
                LocalDate.parse(parts[3], DATE), parts[4]);
            // Only the form this class writes counts as one of ours. Long.parseLong reads "+1248"
            // and "01248" as 1248, so without this an ownership check would answer for a user
            // whose folder is spelt differently from the key the storage backend will fetch --
            // the same divergence between what is authorised and what is read that the traversal
            // check above exists to prevent.
            return parsed.key().equals(trimmed) ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Everything up to and including the trailing slash, for listing one upload. */
    public String prefix() {
        return ROOT + "/" + this.appUserId + "/" + this.uploadId + "/" + DATE.format(this.uploadedOn) + "/";
    }

    public String key() {
        return this.prefix() + this.fileName;
    }

    public Long getAppUserId() { return this.appUserId; }

    public String getUploadId() { return this.uploadId; }

    public LocalDate getUploadedOn() { return this.uploadedOn; }

    public String getFileName() { return this.fileName; }

    /**
     * Reduces a filename to something that cannot escape its folder.
     *
     * Only the last segment is kept, so a name like "../../etc/passwd" collapses to "passwd"
     * rather than being rejected -- the person uploading gets their file under a safe name instead
     * of an error about something they did not knowingly do.
     */
    static String safeFileName(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            return "upload";
        }
        String lastSegment = requested.trim().replace('\\', '/');
        int slash = lastSegment.lastIndexOf('/');
        if (slash >= 0) {
            lastSegment = lastSegment.substring(slash + 1);
        }
        String cleaned = lastSegment.replaceAll(SAFE_FILENAME, "_");
        // parse() refuses any key holding "..", and a dot is a permitted character, so a name
        // like "ca..pem" survived cleaning and stored perfectly well -- and then parsed as
        // nobody's. canUseObject reads the owner out of the parsed key, so it answered no to the
        // very person who had just uploaded the file, and it could never be read, replaced or
        // deleted again.
        cleaned = cleaned.replaceAll("\\.{2,}", ".");
        // A name of only dots would leave the key ending in a directory reference.
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return "upload";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

}
