package process.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.KafkaSecretKind;

/**
 * What the console is told about a Kafka file it just uploaded, or a store the server built.
 *
 * Everything here is safe to render. The bucket and key are needed because the profile the user is
 * about to save has to point at them, and they name a location rather than reveal a secret -- the
 * object behind them is only reachable through the workflow that wrote it, never through the
 * object browser. The summary fields exist so somebody can see they uploaded the right file
 * without opening it: a certificate's subject and expiry answer "is this the production CA or the
 * staging one" at a glance, which is otherwise a keytool command away.
 *
 * What is deliberately absent: any private key material, and any store password in plain text.
 *
 * @author Nabeel Ahmed
 * */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaSecretDto {

    private KafkaSecretKind kind;
    private String bucket;
    private String objectKey;
    private String fileName;
    private String uploadId;
    private String uploadedOn;
    private Long sizeBytes;

    /** Whom the certificate was issued to, and by whom. Null for a key or a pre-built store. */
    private String subject;
    private String issuer;
    private String expiresOn;
    /** Flagged separately so the console can say so plainly rather than making people read a date. */
    private Boolean expired;

    /**
     * The generated store's password, encrypted with the server's own key.
     *
     * It travels because the profile is saved in a later request than the one that built the
     * store, and this is the handle that ties them together. It is AES-GCM ciphertext under a key
     * the browser has never seen, so it discloses nothing; substituting a different value only
     * breaks the substituter's own connection. The alternative -- a server-side table keyed by
     * upload id -- buys nothing except a table.
     */
    private String storePasswordEnc;

    public KafkaSecretKind getKind() { return this.kind; }
    public void setKind(KafkaSecretKind kind) { this.kind = kind; }

    public String getBucket() { return this.bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getObjectKey() { return this.objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getFileName() { return this.fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getUploadId() { return this.uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getUploadedOn() { return this.uploadedOn; }
    public void setUploadedOn(String uploadedOn) { this.uploadedOn = uploadedOn; }

    public Long getSizeBytes() { return this.sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSubject() { return this.subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getIssuer() { return this.issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getExpiresOn() { return this.expiresOn; }
    public void setExpiresOn(String expiresOn) { this.expiresOn = expiresOn; }

    public Boolean getExpired() { return this.expired; }
    public void setExpired(Boolean expired) { this.expired = expired; }

    public String getStorePasswordEnc() { return this.storePasswordEnc; }
    public void setStorePasswordEnc(String storePasswordEnc) { this.storePasswordEnc = storePasswordEnc; }

}
