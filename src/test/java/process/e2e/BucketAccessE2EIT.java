package process.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import process.model.enums.UserRole;
import static org.assertj.core.api.Assertions.assertThat;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two default buckets -- etl-avatar and etl-bucket -- driven over real HTTP.
 *
 * These two belong to the platform, not to any tenant: etl-bucket holds every tenant's Kafka key
 * material under kafka-secrets/{userid}/{uuid}/{date}/, and etl-avatar holds every user's picture.
 * The owner's rule is that only a PLATFORM_ADMIN manages them, with one deliberate hole punched
 * through it -- anybody may put their own picture and their own truststore in. A hole that size is
 * exactly where a mistake hides, so most of what follows is the shape of the hole: whose folder,
 * which bucket, and what a key is allowed to spell.
 *
 * StorageBrowserRestApi's @PreAuthorize is only hasRole('TENANT_USER'), so none of this is decided
 * by the role annotation. It is decided per request, inside StorageBrowserServiceImpl, which means
 * a unit test on the service and a green annotation can both be right while the request is wrong.
 * That is the gap this suite stands in.
 *
 * A note on what is asserted. The guard refuses with exactly "Unknown bucket: <bucket>." and never
 * with a 403 -- it declines to confirm the bucket exists at all, which is why these expect 400 and
 * match the message rather than the status alone. Resolution failing later ("Unknown bucket: <b>.
 * Add a storage connection for it first.") is a different sentence on purpose, so the tests can
 * tell "you may not" from "there is nothing configured here".
 *
 * @author Nabeel Ahmed
 * */
class BucketAccessE2EIT extends E2ESupport {

    /** Named by the owner's rule, and by KafkaSecretService.SECRET_BUCKET, as a shipped default. */
    private static final String KAFKA_BUCKET = "etl-bucket";

    /** A plausible Kafka secret key: the folder a tenant user's own truststore would land in. */
    private static final String KAFKA_SECRET_FOLDER = "kafka-secrets/";

    private static final byte[] PICTURE = "e2e-picture-bytes".getBytes(StandardCharsets.UTF_8);

    // ---- listing ---------------------------------------------------------------------------

    /**
     * The platform admin is the only person who manages these buckets, so the object browser has
     * to offer them at least to him -- otherwise nobody can reach the Kafka material at all.
     */
    @Test
    void aPlatformAdminListingBucketsSeesTheKafkaBucket() throws Exception {
        AppUser admin = this.newPlatformAdmin();

        this.mvc.perform(this.getAs(admin, "/storage.json/buckets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].bucket", hasItem(KAFKA_BUCKET)));
    }

    /**
     * The avatar bucket is the second of the two defaults and is managed the same way, so the same
     * person must be able to see it. Kept apart from the Kafka bucket above so that one missing
     * from the list cannot hide the other.
     */
    @Test
    void aPlatformAdminListingBucketsSeesTheAvatarBucket() throws Exception {
        AppUser admin = this.newPlatformAdmin();

        this.mvc.perform(this.getAs(admin, "/storage.json/buckets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].bucket", hasItem(this.avatarBucketFor(admin))));
    }

    /**
     * A tenant admin runs his own company's account, and neither default bucket is part of it.
     * Offering him the name in a picker is the first step to his tenant's users trying to browse
     * it, so the list is the place to stop it, not just the guard behind it.
     */
    @Test
    void aTenantAdminListingBucketsSeesNeitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("bucket-list-admin");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        String avatarBucket = this.avatarBucketFor(tenantAdmin);
        this.requireBothPlatformBucketsConfigured(avatarBucket);

        this.mvc.perform(this.getAs(tenantAdmin, "/storage.json/buckets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].bucket", not(hasItem(KAFKA_BUCKET))))
            .andExpect(jsonPath("$.data[*].bucket", not(hasItem(avatarBucket))));
    }

    @Test
    void aTenantUserListingBucketsSeesNeitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("bucket-list-user");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);
        this.requireBothPlatformBucketsConfigured(avatarBucket);

        this.mvc.perform(this.getAs(tenantUser, "/storage.json/buckets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].bucket", not(hasItem(KAFKA_BUCKET))))
            .andExpect(jsonPath("$.data[*].bucket", not(hasItem(avatarBucket))));
    }

    // ---- naming the bucket directly ---------------------------------------------------------

    /**
     * Not being offered a bucket is not the same as not being able to reach it: the browser sends
     * whatever bucket name it is given, and a curl can send any name at all. Listing a platform
     * bucket by name is the enumeration step -- it is what tells an attacker which user ids and
     * which tenants have Kafka material before anything is downloaded.
     */
    @Test
    void aTenantUserIsRefusedBrowsingEitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("browse");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.getAs(tenantUser, "/storage.json/listObjects")
            .param("bucket", KAFKA_BUCKET)
            .param("prefix", KAFKA_SECRET_FOLDER), KAFKA_BUCKET);

        this.expectBucketRefused(this.getAs(tenantUser, "/storage.json/listObjects")
            .param("bucket", avatarBucket)
            .param("prefix", ""), avatarBucket);
    }

    /** The same rule applies to a tenant admin: the buckets are the platform's, not a company's. */
    @Test
    void aTenantAdminIsRefusedBrowsingEitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("browse-admin");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        String avatarBucket = this.avatarBucketFor(tenantAdmin);

        this.expectBucketRefused(this.getAs(tenantAdmin, "/storage.json/listObjects")
            .param("bucket", KAFKA_BUCKET)
            .param("prefix", KAFKA_SECRET_FOLDER), KAFKA_BUCKET);

        this.expectBucketRefused(this.getAs(tenantAdmin, "/storage.json/listObjects")
            .param("bucket", avatarBucket)
            .param("prefix", ""), avatarBucket);
    }

    /**
     * Downloading is the payload of the whole thing -- a truststore or a private key read out of
     * kafka-secrets is another tenant's Kafka cluster. The key deliberately names the caller's own
     * appUserId folder, because that is the reading of "his own material" that would most easily
     * be mistaken for permission: the bucket, not the folder, is what settles it.
     */
    @Test
    void aTenantUserIsRefusedDownloadingFromEitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("download");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.getAs(tenantUser, "/storage.json/downloadObject")
            .param("bucket", KAFKA_BUCKET)
            .param("key", this.ownSecretKey(tenantUser)), KAFKA_BUCKET);

        // Reading somebody else's picture out of the avatar bucket, rather than through
        // /appUser.json/avatar, which decides for itself whose faces this caller may see.
        this.expectBucketRefused(this.getAs(tenantUser, "/storage.json/downloadObject")
            .param("bucket", avatarBucket)
            .param("key", (tenantUser.getAppUserId() + 1) + "/profile/avatar.png"), avatarBucket);
    }

    /**
     * Deleting needs no read access to do damage: a tenant whose truststore disappears loses its
     * pipeline, and there is no undo behind an object store.
     */
    @Test
    void aTenantUserIsRefusedDeletingFromEitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("delete");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.deleteAs(tenantUser, "/storage.json/deleteObject")
            .param("bucket", KAFKA_BUCKET)
            .param("key", this.ownSecretKey(tenantUser)), KAFKA_BUCKET);

        this.expectBucketRefused(this.deleteAs(tenantUser, "/storage.json/deleteObject")
            .param("bucket", avatarBucket)
            .param("key", (tenantUser.getAppUserId() + 1) + "/profile/avatar.png"), avatarBucket);

        // Deleting a whole folder, which in etl-bucket is one user's entire set of secrets.
        this.expectBucketRefused(this.deleteAs(tenantUser, "/storage.json/deleteFolder")
            .param("bucket", KAFKA_BUCKET)
            .param("key", KAFKA_SECRET_FOLDER + tenantUser.getAppUserId() + "/"), KAFKA_BUCKET);

        // And the bulk form, which takes its bucket from a body rather than a query parameter --
        // a separate entry point into the same service, so a separate chance to have missed it.
        this.expectBucketRefused(this.postAs(tenantUser, "/storage.json/deleteObjects",
            "{\"bucket\":\"" + KAFKA_BUCKET + "\",\"keys\":[\"" + this.ownSecretKey(tenantUser) + "\"]}"),
            KAFKA_BUCKET);
    }

    /**
     * Renaming is the quiet one: nothing is destroyed, so nothing looks wrong, but every job
     * pointing at the old prefix stops finding its file, and every avatar_key already recorded in
     * app_user stops resolving.
     */
    @Test
    void aTenantUserIsRefusedRenamingAFolderInEitherDefaultBucket() throws Exception {
        Tenant tenant = this.newTenant("rename");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        AppUser neighbour = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.postAs(tenantUser, "/storage.json/renameFolder", null)
            .param("bucket", KAFKA_BUCKET)
            .param("key", KAFKA_SECRET_FOLDER + tenantUser.getAppUserId() + "/")
            .param("newFolderName", "mine"), KAFKA_BUCKET);

        this.expectBucketRefused(this.postAs(tenantUser, "/storage.json/renameFolder", null)
            .param("bucket", avatarBucket)
            .param("key", neighbour.getAppUserId() + "/profile/")
            .param("newFolderName", "mine"), avatarBucket);
    }

    /**
     * The hole in the platform buckets is for adding a picture, not for reorganising a bucket the
     * platform admin owns. Renaming <appUserId>/profile/ is managing storage: it moves the object
     * out from under the avatar_key already recorded against the row, and it is the one verb that
     * reaches the own-profile exception with something other than a file to write.
     *
     * The exception is expressed as "this key is yours", and a folder key ending in /profile/
     * satisfies that test as readily as a file inside it does -- so the refusal has to come from
     * somewhere other than the ownership check for this to hold.
     */
    @Test
    void aTenantUserCannotRenameEvenItsOwnProfileFolder() throws Exception {
        Tenant tenant = this.newTenant("rename-own");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.postAs(tenantUser, "/storage.json/renameFolder", null)
            .param("bucket", avatarBucket)
            .param("key", tenantUser.getAppUserId() + "/profile/")
            .param("newFolderName", "mine"), avatarBucket);
    }

    // ---- the one hole: a user's own picture --------------------------------------------------

    /**
     * The exception the platform depends on. Every user uploads their own picture through this
     * ordinary object endpoint -- the console posts here and then records the key through
     * /appUser.json/updateOwnAvatar -- so if the guard closed over the whole avatar bucket, nobody
     * below a platform admin could ever set a picture.
     *
     * Asserted as "nothing refused this", not as a 200, because whether the bytes land depends on
     * a MinIO being up and an etl-avatar bucket being configured on it, and neither is part of
     * what this suite is testing. Every refusal the service can reach this request with is either
     * a 403 from the role floor or a 400 from one of the two checks in front of the client, and a
     * storage backend that cannot be reached fails as a 500 -- so excluding both refusal codes
     * says "admitted" without depending on the infrastructure. Excluding only the guard's own
     * sentence would have let a traversal check that started refusing honest keys through.
     */
    @Test
    void aTenantUserMayUploadItsOwnPictureIntoTheAvatarBucket() throws Exception {
        Tenant tenant = this.newTenant("own-avatar");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);
        String ownKey = tenantUser.getAppUserId() + "/profile/avatar.png";

        MvcResult result = this.mvc.perform(this.uploadAs(tenantUser, avatarBucket,
            tenantUser.getAppUserId() + "/profile/", "avatar.png")).andReturn();

        assertNotEquals(403, result.getResponse().getStatus(),
            "A user's own picture is the one thing the platform buckets are open for; the role"
                + " floor on StorageBrowserRestApi must admit it.");
        assertNotEquals(400, result.getResponse().getStatus(),
            "Nothing in front of the storage client may refuse a user their own <appUserId>/profile/"
                + " prefix -- neither the platform-bucket guard nor the key check. It answered: "
                + messageOf(result));

        // The one request in this suite that can reach a real bucket, and an object written there
        // is outside the transaction that rolls the rest of this test back. Taken away again so a
        // machine whose MinIO is actually reachable does not collect a stray picture per run,
        // under an appUserId that no longer exists by the time the run ends.
        if (result.getResponse().getStatus() == 200) {
            this.mvc.perform(this.deleteAs(this.newPlatformAdmin(), "/storage.json/deleteObject")
                .param("bucket", avatarBucket)
                .param("key", ownKey));
        }
    }

    /**
     * The other side of the same hole, and the one that matters: the prefix is checked against the
     * caller's own id, so naming a neighbour's folder is a different person's picture and is
     * refused. Nothing in the request distinguishes the two but the number, which is why this is
     * worth a test of its own rather than trusting the one above.
     */
    @Test
    void aTenantUserIsRefusedUploadingIntoAnotherUsersProfileFolder() throws Exception {
        Tenant tenant = this.newTenant("other-avatar");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        AppUser neighbour = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);

        this.expectBucketRefused(this.uploadAs(tenantUser, avatarBucket,
            neighbour.getAppUserId() + "/profile/", "avatar.png"), avatarBucket);
    }

    /**
     * The same-tenant neighbour above is the easy case; a stranger in another company is the one
     * the owner's rule is actually about, and the avatar bucket is the one place both companies'
     * rows sit side by side.
     */
    @Test
    void aTenantUserIsRefusedUploadingIntoAnotherCompanysProfileFolder() throws Exception {
        AppUser mine = this.newUser(UserRole.TENANT_USER, this.newTenant("avatar-us"));
        AppUser theirs = this.newUser(UserRole.TENANT_USER, this.newTenant("avatar-them"));
        String avatarBucket = this.avatarBucketFor(mine);

        this.expectBucketRefused(this.uploadAs(mine, avatarBucket,
            theirs.getAppUserId() + "/profile/", "avatar.png"), avatarBucket);
    }

    /**
     * The exception is a bucket and a folder together, not a folder shape on its own. "<id>/profile/"
     * names nothing anybody owns in etl-bucket, and the folders beside it hold Kafka key material,
     * so an own-profile test that looked only at the key would hand the caller a client for it.
     */
    @Test
    void aTenantUsersOwnProfilePrefixBuysNothingInTheKafkaBucket() throws Exception {
        Tenant tenant = this.newTenant("profile-in-kafka");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);

        this.expectBucketRefused(this.uploadAs(tenantUser, KAFKA_BUCKET,
            tenantUser.getAppUserId() + "/profile/", "avatar.png"), KAFKA_BUCKET);
    }

    // ---- keys that do not mean what they read as ---------------------------------------------

    /**
     * A key with a "." or ".." segment names one object to whoever authorises it and another to
     * whichever backend finally resolves it -- the FTP clients collapse the path themselves, and
     * they do it after the decision has been made. Every endpoint that takes a key is a way in, so
     * every one of them is swept here rather than trusting that the check sits somewhere shared.
     *
     * Run as a platform admin, and against a bucket he may reach, so that a refusal can only be
     * the key check and never the bucket guard standing in front of it.
     */
    @Test
    void aKeyThatClimbsOutOfItsFolderIsRefusedByEveryEndpointThatTakesOne() throws Exception {
        AppUser admin = this.newPlatformAdmin();
        String climbing = KAFKA_SECRET_FOLDER + "17/../../etc/passwd";
        String climbingFolder = KAFKA_SECRET_FOLDER + "17/../../etc/";

        this.expectKeyRefused(this.getAs(admin, "/storage.json/listObjects")
            .param("bucket", KAFKA_BUCKET)
            .param("prefix", climbingFolder), climbingFolder);

        this.expectKeyRefused(this.getAs(admin, "/storage.json/objectMetadata")
            .param("bucket", KAFKA_BUCKET)
            .param("key", climbing), climbing);

        this.expectKeyRefused(this.getAs(admin, "/storage.json/downloadObject")
            .param("bucket", KAFKA_BUCKET)
            .param("key", climbing), climbing);

        this.expectKeyRefused(this.deleteAs(admin, "/storage.json/deleteObject")
            .param("bucket", KAFKA_BUCKET)
            .param("key", climbing), climbing);

        this.expectKeyRefused(this.deleteAs(admin, "/storage.json/deleteFolder")
            .param("bucket", KAFKA_BUCKET)
            .param("key", climbingFolder), climbingFolder);

        this.expectKeyRefused(this.postAs(admin, "/storage.json/renameFolder", null)
            .param("bucket", KAFKA_BUCKET)
            .param("key", climbingFolder)
            .param("newFolderName", "somewhere"), climbingFolder);

        this.expectKeyRefused(this.postAs(admin, "/storage.json/deleteObjects",
            "{\"bucket\":\"" + KAFKA_BUCKET + "\",\"keys\":[\"" + climbing + "\"]}"), climbing);

        this.expectKeyRefused(this.uploadAs(admin, KAFKA_BUCKET, climbingFolder, "payload.txt"),
            climbingFolder);
    }

    /**
     * Stripping the separators out of a folder name leaves ".." standing as a name in its own
     * right, and the two endpoints that build a key from a name rather than taking one whole are
     * the ones where that reads as innocent input.
     */
    @Test
    void aFolderNamedToClimbOutOfItsParentIsRefused() throws Exception {
        AppUser admin = this.newPlatformAdmin();

        this.expectKeyRefused(this.postAs(admin, "/storage.json/createFolder", null)
            .param("bucket", KAFKA_BUCKET)
            .param("prefix", KAFKA_SECRET_FOLDER)
            .param("folderName", ".."), KAFKA_SECRET_FOLDER + "../");

        this.expectKeyRefused(this.postAs(admin, "/storage.json/renameFolder", null)
            .param("bucket", KAFKA_BUCKET)
            .param("key", KAFKA_SECRET_FOLDER + "17/")
            .param("newFolderName", ".."), KAFKA_SECRET_FOLDER + "../");
    }

    /**
     * The avatar exception and the traversal check have to hold together, not merely each on its
     * own: "<mine>/profile/../../<theirs>/profile/" starts with the caller's own folder, so an
     * ownership test that ran before the key was validated would admit it and then write into the
     * neighbour's. This is the single request that breaks the platform if either check is dropped.
     */
    @Test
    void anAvatarUploadCannotClimbOutOfItsOwnProfileFolder() throws Exception {
        Tenant tenant = this.newTenant("avatar-climb");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, tenant);
        AppUser neighbour = this.newUser(UserRole.TENANT_USER, tenant);
        String avatarBucket = this.avatarBucketFor(tenantUser);
        String climbing = tenantUser.getAppUserId() + "/profile/../../"
            + neighbour.getAppUserId() + "/profile/";

        this.expectKeyRefused(this.uploadAs(tenantUser, avatarBucket, climbing, "avatar.png"),
            climbing);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The avatar bucket as the server itself reports it, so the suite follows a redeployment that
     * renames it instead of asserting against a name baked in here.
     */
    private String avatarBucketFor(AppUser user) throws Exception {
        String body = this.mvc.perform(this.getAs(user, "/appUser.json/me"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Object bucket = JsonPath.read(body, "$.data.avatarUploadBucket");
        return String.valueOf(bucket);
    }

    /**
     * The precondition the "a tenant sees neither default bucket" cases rest on: both buckets are
     * actually configured on the installation the suite is pointed at.
     *
     * Without it those cases could not fail. StorageConnectionBootstrap creates neither connection
     * when MINIO_ENDPOINT is unset -- it says so and carries on -- and on a database in that state
     * "the tenant is not offered etl-bucket" is satisfied by nobody being offered it, so they
     * would stay green with the whole tenant narrowing deleted. The two platform-admin cases above
     * do assert the buckets exist, but they run independently: their failing is not what stops
     * these from passing, so the check belongs here as well.
     */
    private void requireBothPlatformBucketsConfigured(String avatarBucket) throws Exception {
        this.mvc.perform(this.getAs(this.newPlatformAdmin(), "/storage.json/buckets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].bucket", hasItem(KAFKA_BUCKET)))
            .andExpect(jsonPath("$.data[*].bucket", hasItem(avatarBucket)));
    }

    /** A key of the shape kafka-secrets/{userid}/{uuid}/{date}/anyfile, for this caller's own id. */
    private String ownSecretKey(AppUser user) {
        return KAFKA_SECRET_FOLDER + user.getAppUserId()
            + "/3f9c2b10-0000-4000-8000-000000000001/2026-08-31/truststore.jks";
    }

    /**
     * The upload endpoint is multipart, so the token goes on by hand rather than through postAs,
     * which would set a JSON content type over it.
     */
    private MockHttpServletRequestBuilder uploadAs(AppUser user, String bucket, String prefix, String fileName) {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "image/png", PICTURE);
        return MockMvcRequestBuilders.multipart("/storage.json/uploadObject")
            .file(file)
            .param("bucket", bucket)
            .param("prefix", prefix)
            .header("Authorization", "Bearer " + this.tokenFor(user));
    }

    /**
     * A refusal by the platform-bucket guard, matched on the exact sentence: the later "…Add a
     * storage connection for it first." means the caller was admitted and found nothing configured,
     * which is not the same answer and must not satisfy a test that a caller was turned away.
     */
    private void expectBucketRefused(MockHttpServletRequestBuilder request, String bucket) throws Exception {
        this.mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(bucketRefusal(bucket)));
    }

    private void expectKeyRefused(MockHttpServletRequestBuilder request, String key) throws Exception {
        this.mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid key: " + key + "."));
    }

    private static String bucketRefusal(String bucket) {
        return "Unknown bucket: " + bucket + ".";
    }

    private static String messageOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            Object message = JsonPath.read(body, "$.message");
            return String.valueOf(message);
        } catch (RuntimeException ex) {
            return body;
        }
    }


    /**
     * The storage screen, not the object browser.
     *
     * These are two different listings and only one of them was ever tested. /storage.json/buckets
     * filters by tenant in its own code; /storageConnection.json trusted the Hibernate filter
     * alone -- and that filter admits the platform's rows on purpose, so the avatar and Kafka
     * workflows can resolve etl-avatar and etl-bucket by alias. Every tenant was therefore shown
     * the platform's two connections on /settings/storage-connections (formerly /admin/storage).
     */
    @Test
    void aTenantAdminDoesNotSeeThePlatformsConnectionsOnTheStorageScreen() throws Exception {
        Tenant company = this.newTenant("ajwa");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, company);

        // Same precondition as requireBothPlatformBucketsConfigured, on the other listing: a
        // database where these two rows were never created answers "no platform connections" to
        // everybody, and doesNotContain would then hold with the narrowing removed.
        String platform = this.mvc.perform(this.getAs(this.newPlatformAdmin(),
                "/storageConnection.json/fetchAllConnections"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(platform)
            .as("the platform's two connections have to exist for hiding them to mean anything")
            .contains("etl-avatar").contains("etl-bucket");

        String body = this.mvc.perform(this.getAs(admin, "/storageConnection.json/fetchAllConnections"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("etl-avatar").doesNotContain("etl-bucket");
    }

    @Test
    void aTenantUserDoesNotSeeThePlatformsConnectionsEither() throws Exception {
        Tenant company = this.newTenant("ajwa");
        AppUser user = this.newUser(UserRole.TENANT_USER, company);

        this.mvc.perform(this.getAs(user, "/storageConnection.json/fetchAllConnections"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aPlatformAdminStillSeesThePlatformsConnections() throws Exception {
        AppUser admin = this.newPlatformAdmin();

        String body = this.mvc.perform(this.getAs(admin, "/storageConnection.json/fetchAllConnections"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("etl-avatar").contains("etl-bucket");
    }


    /**
     * Whether a tenant admin can read a Kafka certificate out of the bucket, as opposed to using it.
     * The download tests above are all a tenant USER; this is the role that legitimately runs the
     * certificate workflow, and so the one most likely to be handed the bucket by mistake.
     *
     * The guard refuses on the bucket before it ever looks for the object, so this holds whether or
     * not the key names something real -- which is what lets it be asserted here, against the real
     * storage service, without writing anything to MinIO that a rollback could not undo.
     *
     * Matched on the guard's own sentence rather than on the 400 alone. resolveService answers a
     * bucket it has no connection for with a 400 too ("...Add a storage connection for it first."),
     * and that is the answer a caller who was ADMITTED gets -- so on the status alone this would
     * have gone on passing with the guard deleted, on any installation where the etl-bucket
     * connection row is missing. StorageConnectionBootstrap leaves it missing whenever
     * MINIO_ENDPOINT is unset, which is not a rare state on a developer's machine.
     */
    @Test
    void aTenantAdminCannotDownloadAKafkaSecretEvenUnderItsOwnUserId() throws Exception {
        Tenant company = this.newTenant("ajwa");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, company);
        String ownKey = KAFKA_SECRET_FOLDER + admin.getAppUserId() + "/uuid/2026-09-01/ca.pem";

        this.expectBucketRefused(this.getAs(admin, "/storage.json/downloadObject")
            .param("bucket", KAFKA_BUCKET)
            .param("key", ownKey), KAFKA_BUCKET);
    }

}
