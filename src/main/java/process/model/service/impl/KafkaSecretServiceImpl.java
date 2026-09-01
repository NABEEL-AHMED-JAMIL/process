package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.KafkaSecretDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.enums.KafkaSecretKind;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.service.KafkaSecretService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.KafkaCertificateUtil;
import process.util.KafkaSecretPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class KafkaSecretServiceImpl implements KafkaSecretService {

    private final Logger logger = LoggerFactory.getLogger(KafkaSecretServiceImpl.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Certificates and keys are a few kilobytes; a store is tens. Anything larger is a mistake. */
    @Value("${kafka.secret.max-file-size-kb:512}")
    private int maxFileSizeKb;

    private final StorageBrowserService storageBrowserService;
    private final AppUserRepository appUserRepository;
    private final EncryptionUtil encryptionUtil;

    public KafkaSecretServiceImpl(StorageBrowserService storageBrowserService,
        AppUserRepository appUserRepository, EncryptionUtil encryptionUtil) {
        this.storageBrowserService = storageBrowserService;
        this.appUserRepository = appUserRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public ResponseDto uploadSecret(MultipartFile file, KafkaSecretKind kind) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "Choose a file to upload.");
        }
        if (kind == null) {
            return new ResponseDto(ERROR, "Say what kind of file this is.");
        }
        Long callerId = TenantContext.getAppUserId();
        if (callerId == null) {
            return new ResponseDto(ERROR, "Kafka files can only be uploaded by a signed-in user.");
        }
        if (file.getSize() > (long) this.maxFileSizeKb * 1024L) {
            return new ResponseDto(ERROR, String.format(
                "That file is larger than the %dKB limit for Kafka certificates.", this.maxFileSizeKb));
        }

        byte[] bytes = this.readAll(file);
        KafkaSecretDto summary;
        try {
            // Parsed before it is stored, not after: a file that is not what it claims to be should
            // be refused while the person is still looking at the form, and refusing early also
            // keeps unreadable rubbish out of the bucket.
            summary = this.describe(bytes, kind);
        } catch (IllegalArgumentException invalid) {
            return new ResponseDto(ERROR, invalid.getMessage());
        }

        KafkaSecretPath path = KafkaSecretPath.newUpload(callerId, file.getOriginalFilename(), LocalDate.now());
        this.storageBrowserService.uploadForWorkflow(SECRET_BUCKET, path.key(),
            new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");

        this.fill(summary, kind, path, (long) bytes.length);
        // The key is logged; the contents never are.
        this.logger.info("Stored Kafka {} for user {} at {}/{}.", kind, callerId, SECRET_BUCKET, path.key());
        return new ResponseDto(SUCCESS, "File uploaded and checked.", summary);
    }

    @Override
    public ResponseDto generateTruststore(List<String> caObjectKeys) throws Exception {
        if (caObjectKeys == null || caObjectKeys.isEmpty()) {
            return new ResponseDto(ERROR, "Choose at least one CA certificate.");
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (String objectKey : caObjectKeys) {
            if (!this.canUseObject(SECRET_BUCKET, objectKey)) {
                return new ResponseDto(ERROR, "That certificate could not be found.");
            }
            try {
                certificates.addAll(KafkaCertificateUtil.readCertificates(this.read(objectKey)));
            } catch (IllegalArgumentException invalid) {
                return new ResponseDto(ERROR, invalid.getMessage());
            }
        }

        String password = this.newStorePassword();
        byte[] store = KafkaCertificateUtil.buildTruststore(certificates, password.toCharArray());
        // Written beside the certificate it was built from, so the pair can be recognised later and
        // removed together when the CA is rotated.
        KafkaSecretPath source = KafkaSecretPath.parse(caObjectKeys.get(0));
        KafkaSecretPath target = source.sibling(generatedStoreName("truststore"));
        this.storageBrowserService.uploadForWorkflow(SECRET_BUCKET, target.key(),
            new ByteArrayInputStream(store), store.length, "application/x-pkcs12");

        KafkaSecretDto dto = new KafkaSecretDto();
        this.fill(dto, KafkaSecretKind.TRUSTSTORE, target, (long) store.length);
        dto.setStorePasswordEnc(this.encryptionUtil.encrypt(password));
        this.logger.info("Built a truststore for user {} from {} certificate(s) at {}.",
            TenantContext.getAppUserId(), certificates.size(), target.key());
        return new ResponseDto(SUCCESS, String.format(
            "Truststore built from %d certificate%s.", certificates.size(),
            certificates.size() == 1 ? "" : "s"), dto);
    }

    @Override
    public ResponseDto generateKeystore(String certificateObjectKey, String privateKeyObjectKey) throws Exception {
        if (certificateObjectKey == null || privateKeyObjectKey == null) {
            return new ResponseDto(ERROR, "A keystore needs both the client certificate and its private key.");
        }
        if (!this.canUseObject(SECRET_BUCKET, certificateObjectKey)
            || !this.canUseObject(SECRET_BUCKET, privateKeyObjectKey)) {
            return new ResponseDto(ERROR, "Those files could not be found.");
        }

        List<X509Certificate> chain;
        PrivateKey privateKey;
        try {
            chain = KafkaCertificateUtil.readCertificates(this.read(certificateObjectKey));
            privateKey = KafkaCertificateUtil.readPrivateKey(this.read(privateKeyObjectKey));
        } catch (IllegalArgumentException invalid) {
            return new ResponseDto(ERROR, invalid.getMessage());
        }
        if (!KafkaCertificateUtil.keyMatchesCertificate(privateKey, chain.get(0))) {
            // Caught here rather than at the handshake, which happens on a different machine hours
            // later and says only that the connection failed.
            return new ResponseDto(ERROR,
                "That private key does not belong to that certificate. Check you uploaded the pair "
                + "the broker issued together.");
        }

        String password = this.newStorePassword();
        byte[] store = KafkaCertificateUtil.buildKeystore(privateKey, chain, password.toCharArray());
        KafkaSecretPath target = KafkaSecretPath.parse(certificateObjectKey)
            .sibling(generatedStoreName("keystore"));
        this.storageBrowserService.uploadForWorkflow(SECRET_BUCKET, target.key(),
            new ByteArrayInputStream(store), store.length, "application/x-pkcs12");

        KafkaSecretDto dto = new KafkaSecretDto();
        this.fill(dto, KafkaSecretKind.KEYSTORE, target, (long) store.length);
        dto.setStorePasswordEnc(this.encryptionUtil.encrypt(password));
        this.logger.info("Built a keystore for user {} at {}.", TenantContext.getAppUserId(), target.key());
        return new ResponseDto(SUCCESS, "Keystore built from the certificate and key.", dto);
    }

    /**
     * Who may point a connection profile at a stored file.
     *
     * A tenant admin is included because it owns its tenant's connections and would otherwise be
     * unable to finish a profile one of its own users started. It is resolved through the owner's
     * tenant rather than the key, because the key names a user and says nothing about which tenant
     * that user is in.
     *
     * That reach stops at tenant users, the same line AppUserServiceImpl draws on the users
     * screen: an administrator's authority runs over the people it manages, not over its peers.
     * Sharing a tenant was enough on its own here, so one administrator could attach another's
     * client private key to a profile of its own and speak to a broker as them.
     */
    @Override
    public boolean canUseObject(String bucket, String objectKey) {
        if (!SECRET_BUCKET.equals(bucket)) {
            return false;
        }
        KafkaSecretPath path = KafkaSecretPath.parse(objectKey);
        if (path == null) {
            return false;
        }
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        Long caller = TenantContext.getAppUserId();
        if (caller != null && caller.equals(path.getAppUserId())) {
            return true;
        }
        if (!"TENANT_ADMIN".equals(TenantContext.getUserRole()) || TenantContext.getTenantId() == null) {
            return false;
        }
        Optional<AppUser> owner = this.appUserRepository.findById(path.getAppUserId());
        return owner.isPresent()
            && TenantContext.getTenantId().equals(owner.get().getTenantId())
            && owner.get().getUserRole() == UserRole.TENANT_USER;
    }

    /** Checks the bytes really are what the caller called them, and summarises what was found. */
    private KafkaSecretDto describe(byte[] bytes, KafkaSecretKind kind) {
        KafkaSecretDto dto = new KafkaSecretDto();
        if (kind.isCertificate()) {
            List<X509Certificate> certificates = KafkaCertificateUtil.readCertificates(bytes);
            X509Certificate first = certificates.get(0);
            dto.setSubject(this.commonName(first.getSubjectX500Principal().getName()));
            dto.setIssuer(this.commonName(first.getIssuerX500Principal().getName()));
            dto.setExpiresOn(DAY.format(first.getNotAfter().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate()));
            dto.setExpired(first.getNotAfter().before(new java.util.Date()));
            return dto;
        }
        if (kind == KafkaSecretKind.CLIENT_PRIVATE_KEY) {
            // Parsed and thrown away. The point is the refusal message, which names PKCS#1 and
            // encrypted keys specifically -- both are what openssl hands people by default.
            KafkaCertificateUtil.readPrivateKey(bytes);
            return dto;
        }
        // A pre-built store cannot be opened without its password, which the profile carries and
        // this request does not. Accepted as given; a wrong one surfaces when the connection is
        // tested, which is the earliest point anything could tell.
        if (bytes.length == 0) {
            throw new IllegalArgumentException("That store file is empty.");
        }
        return dto;
    }

    private void fill(KafkaSecretDto dto, KafkaSecretKind kind, KafkaSecretPath path, Long size) {
        dto.setKind(kind);
        dto.setBucket(SECRET_BUCKET);
        dto.setObjectKey(path.key());
        dto.setFileName(path.getFileName());
        dto.setUploadId(path.getUploadId());
        dto.setUploadedOn(DAY.format(path.getUploadedOn()));
        dto.setSizeBytes(size);
    }

    private byte[] read(String objectKey) throws Exception {
        ObjectContentDto content = this.storageBrowserService.readForWorkflow(SECRET_BUCKET, objectKey);
        try (InputStream in = content.getContent()) {
            return this.drain(in);
        }
    }

    private byte[] readAll(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            return this.drain(in);
        }
    }

    private byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * A password for a store the server both writes and reads.
     *
     * Random and never shown to anybody: a PKCS12 file has to have one, but nothing here needs a
     * human to type it, so making it memorable would only weaken it. It is stored encrypted on the
     * profile and decrypted when the Kafka client is built.
     */
    private String newStorePassword() {
        return UUID.randomUUID().toString() + UUID.randomUUID().toString();
    }

    /**
     * A name of its own for each generated store, in the folder of the certificate it came from.
     *
     * The folder keeps the pair together; the suffix is what stops a second generation writing
     * over the first. They are not interchangeable files -- each store carries its own random
     * password, held encrypted on whichever profile was saved against it -- so a fixed name meant
     * building a second store from the same certificate left every earlier profile pointing at a
     * file its password no longer opens, and nothing said so until the next handshake.
     */
    private static String generatedStoreName(String kind) {
        return kind + "-" + UUID.randomUUID().toString().substring(0, 8) + ".p12";
    }

    private String commonName(String distinguishedName) {
        if (distinguishedName == null) {
            return null;
        }
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return trimmed.substring(3).trim();
            }
        }
        return distinguishedName;
    }

}
