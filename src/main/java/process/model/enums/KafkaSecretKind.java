package process.model.enums;

/**
 * The kinds of file a Kafka connection can be given.
 *
 * Named rather than inferred from the extension, because the same .pem holds a CA certificate, a
 * client certificate or a private key depending on who exported it, and guessing wrong means
 * either a confusing rejection or a truststore built out of the wrong thing. The caller says what
 * it is and the server checks that claim by parsing the file.
 *
 * @author Nabeel Ahmed
 * */
public enum KafkaSecretKind {

    /** The certificate authority that signed the broker. Becomes a truststore. */
    CA_CERTIFICATE,

    /** This client's own certificate, for mutual TLS. Becomes a keystore with the key below. */
    CLIENT_CERTIFICATE,

    /** The private key belonging to the client certificate. Never leaves the server again. */
    CLIENT_PRIVATE_KEY,

    /** An already-built truststore, for somebody who ran keytool themselves. */
    TRUSTSTORE,

    /** An already-built keystore, same. */
    KEYSTORE;

    public boolean isCertificate() {
        return this == CA_CERTIFICATE || this == CLIENT_CERTIFICATE;
    }

    public boolean isPreBuiltStore() {
        return this == TRUSTSTORE || this == KEYSTORE;
    }

}
