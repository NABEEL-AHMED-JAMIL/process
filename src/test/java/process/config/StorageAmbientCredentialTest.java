package process.config;

import org.junit.jupiter.api.Test;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that stops a tenant's S3 connection from running as the platform itself.
 *
 * An S3 connection saved with no keys used to fall through to the AWS SDK's default credential
 * chain, i.e. the IAM role the process runs as. Anyone able to create a connection could then
 * point it at any bucket in the account and read it. Keys are now required, and the ambient
 * path is both opt-in and platform-only.
 *
 * @author Nabeel Ahmed
 */
public class StorageAmbientCredentialTest {

    /** No Spring context here, so the property-backed flag is set the way Spring would. */
    private StorageClientFactory factoryWithInstanceRole(boolean allowed) throws Exception {
        StorageClientFactory factory = new StorageClientFactory(null, null);
        Field field = StorageClientFactory.class.getDeclaredField("allowInstanceRole");
        field.setAccessible(true);
        field.setBoolean(factory, allowed);
        return factory;
    }

    private StorageConnection s3ConnectionWithNoKeys(Long tenantId) {
        StorageConnection connection = new StorageConnection();
        connection.setProvider(StorageProvider.S3);
        connection.setAlias("mine");
        connection.setBucketName("company-payroll-prod");
        connection.setTenantId(tenantId);
        return connection;
    }

    @Test
    void aTenantsKeylessConnectionIsRefusedOutright() throws Exception {
        StorageClientFactory factory = this.factoryWithInstanceRole(false);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> factory.buildUncached(this.s3ConnectionWithNoKeys(42L)));
        assertTrue(thrown.getMessage().contains("access key"),
            "the refusal should say what is missing: " + thrown.getMessage());
    }

    @Test
    void aKeylessConnectionIsRefusedEvenWithoutATenant() throws Exception {
        // Platform-level, but the deployment never opted in -- so still no ambient identity.
        StorageClientFactory factory = this.factoryWithInstanceRole(false);
        assertThrows(IllegalStateException.class,
            () -> factory.buildUncached(this.s3ConnectionWithNoKeys(null)));
    }

    @Test
    void theHostsIdentityIsOffUntilADeploymentAsksForIt() throws Exception {
        assertFalse(this.factoryWithInstanceRole(false).ambientCredentialsAllowed(null),
            "storage.allow-instance-role defaults to off");
    }

    @Test
    void turningItOnDoesNotOpenItToATenant() throws Exception {
        StorageClientFactory factory = this.factoryWithInstanceRole(true);
        assertFalse(factory.ambientCredentialsAllowed(42L),
            "a tenant's connection must never run as the platform");
    }

    @Test
    void onlyAPlatformConnectionMayUseIt() throws Exception {
        StorageClientFactory factory = this.factoryWithInstanceRole(true);
        assertTrue(factory.ambientCredentialsAllowed(null));
    }

}
