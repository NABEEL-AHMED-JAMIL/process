package process.model.service.impl;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import process.model.dto.ObjectSummaryDto;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FTP listing cache is shared by every FTP connection. Aliases are unique per tenant only
 * (MIG-53), so two tenants may each have a connection called "ftp"; a cache keyed by alias handed
 * one tenant the other's folder listing for up to the cache's TTL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FtpListingCacheIsolationTest {

    private FtpServer ftp;
    private int port;

    @BeforeAll
    void startFtp() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            this.port = free.getLocalPort();
        }
        FtpServerFactory server = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setPort(this.port);
        server.addListener("default", listener.createListener());
        for (String tenant : new String[] {"acme", "globex"}) {
            Path home = Files.createTempDirectory("ftp-" + tenant);
            Files.write(home.resolve(tenant + "-payroll.csv"), tenant.getBytes(StandardCharsets.UTF_8));
            BaseUser user = new BaseUser();
            user.setName(tenant);
            user.setPassword(tenant + "-secret");
            user.setHomeDirectory(home.toString());
            user.setAuthorities(Collections.<Authority>singletonList(new WritePermission()));
            server.getUserManager().save(user);
        }
        this.ftp = server.createServer();
        this.ftp.start();
    }

    @AfterAll
    void stopFtp() {
        this.ftp.stop();
    }

    @Test
    void twoTenantsWithTheSameAliasNeverSeeEachOthersListing() {
        Cache shared = new ConcurrentMapCache("ftpListing");
        FtpObjectStorageServiceImpl acme = this.store(101L, 1L, "acme", shared);
        FtpObjectStorageServiceImpl globex = this.store(102L, 2L, "globex", shared);

        assertThat(names(acme)).containsExactly("acme-payroll.csv");
        assertThat(names(globex)).as("globex's own folder, not acme's cached one").containsExactly("globex-payroll.csv");
    }

    private FtpObjectStorageServiceImpl store(long id, long tenantId, String user, Cache cache) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setTenantId(tenantId);
        connection.setAlias("ftp");
        connection.setProvider(StorageProvider.FTP);
        connection.setHost("localhost");
        connection.setPort(this.port);
        connection.setUsername(user);
        connection.setBaseDirectory("/");
        connection.setPassiveMode(true);
        return new FtpObjectStorageServiceImpl(connection, user + "-secret", cache);
    }

    private static List<String> names(FtpObjectStorageServiceImpl store) {
        return store.listObjects("ftp", "", null, 50).getObjects().stream()
            .map(ObjectSummaryDto::getName).collect(Collectors.toList());
    }
}
