package process.model.service.impl;

import org.apache.commons.net.ftp.FTPClient;
import org.springframework.cache.Cache;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.pojo.StorageConnection;
import process.model.service.ObjectStorageService;
import process.util.ContentTypeUtil;
import process.util.SessionReusingFtpsClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Maps the object-store shaped ObjectStorageService contract onto a plain FTP/FTPS server:
 * "prefix" becomes a directory path under the connection's base directory, and a "key" is a
 * path relative to that base. Everything stays inside the base directory -- see requirePath().
 *
 * Each call opens and closes its own connection rather than holding one open: FTP control
 * connections are stateful (current working directory, transfer mode) and idle-timeout
 * aggressively on most servers, so a shared long-lived client would both leak state between
 * concurrent callers and break unpredictably after periods of inactivity.
 */
public class FtpObjectStorageServiceImpl implements ObjectStorageService {

    /** Short-lived cache of directory listings; null disables caching entirely. */
    private final Cache listingCache;

    private static final Logger logger = LoggerFactory.getLogger(FtpObjectStorageServiceImpl.class);
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int DATA_TIMEOUT_MS = 60000;

    private final StorageConnection connection;
    private final String password;

    public FtpObjectStorageServiceImpl(StorageConnection connection, String password) {
        this(connection, password, null);
    }

    public FtpObjectStorageServiceImpl(StorageConnection connection, String password, Cache listingCache) {
        this.listingCache = listingCache;
        this.connection = connection;
        this.password = password;
    }

    // --- connection lifecycle ---------------------------------------------------------

    private FTPClient connect() {
        boolean secure = this.connection.getProvider() != null && this.connection.getProvider().isFtpFamily()
            && "FTPS".equals(this.connection.getProvider().name());
        FTPClient client = secure
            ? new SessionReusingFtpsClient(Boolean.TRUE.equals(this.connection.getImplicitTls()))
            : new FTPClient();
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setDefaultTimeout(CONNECT_TIMEOUT_MS);
        try {
            int port = this.connection.getPort() != null && this.connection.getPort() > 0
                ? this.connection.getPort()
                : (secure && Boolean.TRUE.equals(this.connection.getImplicitTls()) ? 990 : 21);
            client.connect(this.connection.getHost(), port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                this.silentDisconnect(client);
                throw new IllegalStateException("FTP server refused the connection: " + client.getReplyString());
            }
            if (!client.login(this.connection.getUsername(), this.password)) {
                this.silentDisconnect(client);
                throw new IllegalStateException("FTP login failed for user " + this.connection.getUsername() + ".");
            }
            if (secure) {
                FTPSClient ftpsClient = (FTPSClient) client;
                // Without these the data channel would fall back to plaintext even though the
                // control channel is encrypted -- i.e. file contents would cross the wire in
                // the clear on a connection the user explicitly asked to be FTPS.
                ftpsClient.execPBSZ(0);
                ftpsClient.execPROT("P");
            }
            client.setDataTimeout(DATA_TIMEOUT_MS);
            client.setFileType(FTPClient.BINARY_FILE_TYPE);
            if (this.connection.getPassiveMode() == null || Boolean.TRUE.equals(this.connection.getPassiveMode())) {
                client.enterLocalPassiveMode();
            } else {
                client.enterLocalActiveMode();
            }
            return client;
        } catch (IOException e) {
            this.silentDisconnect(client);
            throw new UncheckedIOException("Could not connect to " + this.connection.getHost() + ": " + e.getMessage(), e);
        }
    }

    private void disconnect(FTPClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.logout();
            }
        } catch (IOException ignored) {
            // logout is best-effort; the disconnect below is what actually frees the socket
        } finally {
            this.silentDisconnect(client);
        }
    }

    private void silentDisconnect(FTPClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
            // nothing useful to do -- the socket is going away either way
        }
    }

    private interface FtpCallback<T> {
        T run(FTPClient client) throws IOException;
    }

    private <T> T withClient(FtpCallback<T> callback) {
        FTPClient client = this.connect();
        try {
            return callback.run(client);
        } catch (IOException e) {
            throw new UncheckedIOException("FTP operation failed: " + e.getMessage(), e);
        } finally {
            this.disconnect(client);
        }
    }

    // --- path handling ----------------------------------------------------------------

    private String baseDir() {
        String base = this.connection.getBaseDirectory();
        if (base == null || base.trim().isEmpty()) {
            return "/";
        }
        String trimmed = base.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * Resolves a caller-supplied key/prefix to an absolute server path, and refuses anything
     * that would escape the configured base directory. Without this check a key like
     * "../../etc/passwd" would let any user of one FTP connection read the whole filesystem
     * the FTP account can reach, well outside the directory the connection was scoped to.
     */
    private String requirePath(String keyOrPrefix) {
        String relative = keyOrPrefix == null ? "" : keyOrPrefix.trim();
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        String base = this.baseDir();
        String combined = base + relative;
        String normalized = this.normalize(combined);
        String normalizedBase = this.normalize(base);
        if (!normalized.equals(this.stripTrailingSlash(normalizedBase))
            && !normalized.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Path escapes the connection's base directory: " + keyOrPrefix);
        }
        return normalized;
    }

    private String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(segment);
        }
        String joined = "/" + String.join("/", parts);
        return path.endsWith("/") && !"/".equals(joined) ? joined + "/" : joined;
    }

    private String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private String relativeKey(String absolutePath) {
        String base = this.normalize(this.baseDir());
        String normalized = this.normalize(absolutePath);
        return normalized.startsWith(base) ? normalized.substring(base.length()) : normalized;
    }

    private String fileNameOf(String key) {
        String trimmed = this.stripTrailingSlash(key);
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    /**
     * Commons Net's listFiles() returns an EMPTY ARRAY rather than throwing when the data
     * connection fails, so a server refusing the transfer (e.g. "425 Cannot secure data
     * connection - TLS session resumption required") is indistinguishable from a genuinely
     * empty directory. Checking the reply code afterwards separates the two, so a failure is
     * reported as one instead of quietly looking like a successful listing of nothing.
     */
    private FTPFile[] listChecked(FTPClient client, String path) throws IOException {
        FTPFile[] files = client.listFiles(path);
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply) && !FTPReply.isPositivePreliminary(reply)) {
            String detail = client.getReplyString();
            throw new IOException("Could not list " + (path == null || path.isEmpty() ? "/" : path)
                + " -- " + (detail == null ? ("FTP reply " + reply) : detail.trim()));
        }
        return files == null ? new FTPFile[0] : files;
    }

    // --- ObjectStorageService ---------------------------------------------------------

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        String path = this.requirePath(prefix);
        List<ObjectSummaryDto> objects = this.listDirectoryCached(path);
        // FTP's LIST has no server-side paging or cursor, so the whole directory comes back at
        // once -- cap it to the caller's page size and report no continuation.
        List<ObjectSummaryDto> page = objects.size() > maxKeys ? objects.subList(0, maxKeys) : objects;
        return new BrowseObjectsResponseDto(new ArrayList<>(page), null);
    }

    /**
     * A single FTP LIST costs a full connect + login (+ TLS handshake when secured), and the
     * cost is the same whatever page size the caller asked for -- maxKeys is applied in memory
     * afterwards. Caching the built list per directory therefore lets one round trip serve every
     * request for that folder, including the separate folder-insights pass that asks for the
     * same directory with a different page size.
     */
    @SuppressWarnings("unchecked")
    private List<ObjectSummaryDto> listDirectoryCached(String path) {
        String cacheKey = this.connection.getAlias() + ":" + path;
        if (this.listingCache != null) {
            Cache.ValueWrapper cached = this.listingCache.get(cacheKey);
            if (cached != null && cached.get() instanceof List) {
                return (List<ObjectSummaryDto>) cached.get();
            }
        }
        List<ObjectSummaryDto> objects = this.listDirectory(path);
        if (this.listingCache != null) {
            this.listingCache.put(cacheKey, objects);
        }
        return objects;
    }

    /** Drops every cached listing for this connection -- called after anything that writes. */
    private void invalidateListingCache() {
        if (this.listingCache != null) {
            this.listingCache.clear();
        }
    }

    private List<ObjectSummaryDto> listDirectory(String path) {
        return this.withClient(client -> {
            FTPFile[] files = this.listChecked(client, path);
            List<ObjectSummaryDto> objects = new ArrayList<>();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            for (FTPFile file : files) {
                if (file == null || ".".equals(file.getName()) || "..".equals(file.getName())) {
                    continue;
                }
                boolean isDirectory = file.isDirectory();
                String childPath = this.stripTrailingSlash(path) + "/" + file.getName() + (isDirectory ? "/" : "");
                String key = this.relativeKey(childPath);
                String lastModified = file.getTimestamp() != null
                    ? formatter.format(file.getTimestamp().getTime())
                    : null;
                objects.add(new ObjectSummaryDto(
                    file.getName(),
                    key,
                    isDirectory,
                    isDirectory ? null : file.getSize(),
                    lastModified));
            }
            // Object stores return folders ahead of files; match that so the browser's
            // ordering doesn't change depending on which provider is selected.
            objects.sort(Comparator
                .comparing(ObjectSummaryDto::isFolder).reversed()
                .thenComparing(o -> o.getName() == null ? "" : o.getName().toLowerCase()));
            return objects;
        });
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        String path = this.requirePath(key);
        return this.withClient(client -> {
            FTPFile file = this.statFile(client, path);
            if (file == null) {
                throw new IllegalArgumentException("Not found on the FTP server: " + key);
            }
            String lastModified = file.getTimestamp() != null
                ? new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(file.getTimestamp().getTime())
                : null;
            String name = this.fileNameOf(key);
            return new ObjectMetadataDto(name, key, file.getSize(), lastModified, null,
                ContentTypeUtil.contentTypeFor(name), ContentTypeUtil.isPreviewable(name));
        });
    }

    private FTPFile statFile(FTPClient client, String path) throws IOException {
        // mlistFile is the precise, machine-readable stat, but plenty of servers don't
        // implement MLST -- fall back to listing the parent and matching by name.
        try {
            FTPFile viaMlst = client.mlistFile(path);
            if (viaMlst != null) {
                return viaMlst;
            }
        } catch (IOException ignored) {
            // server doesn't support MLST; fall through to the LIST-based lookup
        }
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "/";
        String target = this.fileNameOf(path);
        FTPFile[] siblings = this.listChecked(client, parent.isEmpty() ? "/" : parent);
        for (FTPFile sibling : siblings) {
            if (sibling != null && target.equals(sibling.getName())) {
                return sibling;
            }
        }
        return null;
    }

    @Override
    public ObjectContentDto getObjectContent(String bucket, String key, Long rangeStart, Long rangeEnd) {
        String path = this.requirePath(key);
        String name = this.fileNameOf(key);
        return this.withClient(client -> {
            // Buffered into memory rather than streamed: the InputStream has to outlive this
            // method (the caller reads it after we return), but the FTP connection backing it
            // is closed in the finally block -- a live stream would be dead on arrival.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (rangeStart != null && rangeStart > 0) {
                client.setRestartOffset(rangeStart);
            }
            if (!client.retrieveFile(path, buffer)) {
                throw new IllegalArgumentException(
                    "Could not read " + key + " from the FTP server: " + client.getReplyString());
            }
            byte[] bytes = buffer.toByteArray();
            if (rangeEnd != null && rangeStart != null) {
                int length = (int) Math.min(bytes.length, rangeEnd - rangeStart + 1);
                if (length > 0 && length < bytes.length) {
                    byte[] sliced = new byte[length];
                    System.arraycopy(bytes, 0, sliced, 0, length);
                    bytes = sliced;
                }
            }
            return new ObjectContentDto(new ByteArrayInputStream(bytes),
                ContentTypeUtil.contentTypeFor(name), bytes.length, bytes.length, name);
        });
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream stream, long size, String contentType) {
        this.invalidateListingCache();
        String path = this.requirePath(key);
        this.withClient(client -> {
            this.ensureParentDirectories(client, path);
            if (!client.storeFile(path, stream)) {
                throw new IllegalStateException(
                    "Upload of " + key + " failed: " + client.getReplyString());
            }
            return null;
        });
    }

    private void ensureParentDirectories(FTPClient client, String path) throws IOException {
        String parent = path.substring(0, path.lastIndexOf('/'));
        String base = this.stripTrailingSlash(this.normalize(this.baseDir()));
        if (parent.isEmpty() || parent.equals(base) || !parent.startsWith(base)) {
            return;
        }
        String relative = parent.substring(base.length());
        String current = base;
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current + "/" + segment;
            // makeDirectory returns false when it already exists, which is fine here --
            // the goal is only that the path exists by the time we store into it.
            client.makeDirectory(current);
        }
    }

    @Override
    public void createFolder(String bucket, String folderKey) {
        this.invalidateListingCache();
        String path = this.stripTrailingSlash(this.requirePath(folderKey));
        this.withClient(client -> {
            this.ensureParentDirectories(client, path + "/x");
            if (!client.makeDirectory(path) && this.statFileQuietly(client, path) == null) {
                throw new IllegalStateException("Could not create folder " + folderKey + ": " + client.getReplyString());
            }
            return null;
        });
    }

    private FTPFile statFileQuietly(FTPClient client, String path) {
        try {
            return this.statFile(client, path);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        this.invalidateListingCache();
        String path = this.requirePath(key);
        this.withClient(client -> {
            if (!client.deleteFile(path)) {
                throw new IllegalStateException("Could not delete " + key + ": " + client.getReplyString());
            }
            return null;
        });
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        this.invalidateListingCache();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // One connection for the whole batch rather than one per key -- deleting a
        // multi-select of 50 files shouldn't mean 50 logins.
        this.withClient(client -> {
            for (String key : keys) {
                String path = this.requirePath(key);
                if (key.endsWith("/")) {
                    this.removeTree(client, this.stripTrailingSlash(path));
                } else if (!client.deleteFile(path)) {
                    logger.warn("FTP delete failed for {}: {}", key, client.getReplyString());
                }
            }
            return null;
        });
    }

    @Override
    public void deleteFolder(String bucket, String folderPrefix) {
        this.invalidateListingCache();
        String path = this.stripTrailingSlash(this.requirePath(folderPrefix));
        this.withClient(client -> {
            this.removeTree(client, path);
            return null;
        });
    }

    /** FTP's RMD only removes empty directories, so the tree has to be cleared depth-first. */
    private void removeTree(FTPClient client, String path) throws IOException {
        FTPFile[] children = this.listChecked(client, path);
        for (FTPFile child : children) {
            if (child == null || ".".equals(child.getName()) || "..".equals(child.getName())) {
                continue;
            }
            String childPath = path + "/" + child.getName();
            if (child.isDirectory()) {
                this.removeTree(client, childPath);
            } else {
                client.deleteFile(childPath);
            }
        }
        client.removeDirectory(path);
    }

    @Override
    public void renameFolder(String bucket, String oldPrefix, String newPrefix) {
        this.invalidateListingCache();
        String from = this.stripTrailingSlash(this.requirePath(oldPrefix));
        String to = this.stripTrailingSlash(this.requirePath(newPrefix));
        this.withClient(client -> {
            if (!client.rename(from, to)) {
                throw new IllegalStateException(
                    "Could not rename " + oldPrefix + " to " + newPrefix + ": " + client.getReplyString());
            }
            return null;
        });
    }

    /** Used by the "Test Connection" action -- logs in, verifies the base directory is reachable. */
    public String testConnection() {
        return this.withClient(client -> {
            String base = this.stripTrailingSlash(this.normalize(this.baseDir()));
            FTPFile[] files = this.listChecked(client, base.isEmpty() ? "/" : base);
            return "Connected to " + this.connection.getHost() + " -- base directory "
                + this.baseDir() + " is readable (" + (files == null ? 0 : files.length) + " entries).";
        });
    }

}
