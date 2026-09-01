package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ResponseDto;
import process.model.enums.KafkaSecretKind;
import java.util.List;

/**
 * Storage for the certificates and key material a Kafka connection needs.
 *
 * Kept apart from KafkaConnectionProfileService because the two answer different questions. A
 * profile is a row somebody edits; this is a file somebody hands over, which has to be validated
 * before it is believed, placed somewhere only its owner can reach, and in most cases converted
 * into the store format a Kafka client actually wants.
 *
 * @author Nabeel Ahmed
 * */
public interface KafkaSecretService {

    /**
     * The platform bucket every Kafka file lives in.
     *
     * Not configurable per tenant on purpose: key material is the one thing that should not sit in
     * a bucket whose credentials a tenant holds, because anyone with those credentials can read it
     * outside the application entirely.
     */
    String SECRET_BUCKET = "etl-bucket";

    /**
     * Stores one uploaded file after checking it really is what the caller says it is.
     *
     * The location is chosen entirely by the server -- see KafkaSecretPath -- so a caller can
     * neither overwrite somebody else's file nor place one where it would be served from.
     */
    ResponseDto uploadSecret(MultipartFile file, KafkaSecretKind kind) throws Exception;

    /**
     * Builds a truststore from one or more previously uploaded CA certificates.
     *
     * Separate from the upload so a chain arriving as several files ends up in one store, and so
     * that re-generating after a CA rotation does not mean re-uploading everything.
     */
    ResponseDto generateTruststore(List<String> caObjectKeys) throws Exception;

    /** Builds a keystore from an uploaded client certificate and its private key, for mutual TLS. */
    ResponseDto generateKeystore(String certificateObjectKey, String privateKeyObjectKey) throws Exception;

    /**
     * Whether the caller may attach this object to a connection profile.
     *
     * Called when a profile is saved, because the profile carries a bucket and key that arrived
     * from the browser and would otherwise be taken on trust.
     */
    boolean canUseObject(String bucket, String objectKey);

}
