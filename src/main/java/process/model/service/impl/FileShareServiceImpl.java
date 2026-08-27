package process.model.service.impl;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.emailer.EmailMessagesFactory;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileShareRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.dto.ResponseDto;
import process.model.service.FileShareService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class FileShareServiceImpl implements FileShareService {

    private static final Logger logger = LoggerFactory.getLogger(FileShareServiceImpl.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final long MAX_SHARE_BYTES = 20L * 1024 * 1024;

    private static final int MAX_SHARE_FILES = 500;

    private final StorageBrowserService storageBrowserService;
    private final EmailMessagesFactory emailMessagesFactory;

    public FileShareServiceImpl(StorageBrowserService storageBrowserService, EmailMessagesFactory emailMessagesFactory) {
        this.storageBrowserService = storageBrowserService;
        this.emailMessagesFactory = emailMessagesFactory;
    }

    @Override
    public ResponseDto emailFile(FileShareRequestDto dto) throws Exception {
        if (isNull(dto.getBucket()) || dto.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        boolean bulk = dto.getKeys() != null && !dto.getKeys().isEmpty();
        if (!bulk && (isNull(dto.getKey()) || dto.getKey().trim().isEmpty())) {
            return new ResponseDto(ERROR, "key missing.");
        }
        if (isNull(dto.getRecipientEmail()) || !EMAIL_PATTERN.matcher(dto.getRecipientEmail().trim()).matches()) {
            return new ResponseDto(ERROR, "Enter a valid recipient email address.");
        }
        boolean bucketOwnedByCaller = this.storageBrowserService.listBuckets().stream()
            .map(BucketSummaryDto::getBucket)
            .anyMatch(dto.getBucket()::equals);
        if (!bucketOwnedByCaller) {
            return new ResponseDto(ERROR, String.format("Unknown bucket: %s.", dto.getBucket()));
        }

        String senderName = TenantContext.getUsername();

        try {
            if (bulk) {
                return this.emailSelection(dto, senderName, dto.getKeys());
            }
            return this.emailSelection(dto, senderName, java.util.Collections.singletonList(dto.getKey()));
        } catch (IllegalArgumentException limitExceeded) {
            return new ResponseDto(ERROR, limitExceeded.getMessage());
        } catch (Exception ex) {
            logger.error("File Share: emailFile failed for bucket={} key={} keys={}", dto.getBucket(), dto.getKey(), dto.getKeys(), ex);
            return new ResponseDto(ERROR, "Could not send this email: " + ex.getMessage());
        }
    }

    private ResponseDto emailSelection(FileShareRequestDto dto, String senderName, List<String> keys) throws Exception {
        if (keys.size() == 1) {
            String onlyKey = keys.get(0);
            return onlyKey.endsWith("/")
                ? this.emailFolder(dto.getBucket(), onlyKey, dto.getRecipientEmail(), dto.getMessage(), senderName)
                : this.emailSingleFile(dto.getBucket(), onlyKey, dto.getRecipientEmail(), dto.getMessage(), senderName);
        }
        List<ObjectSummaryDto> files = new ArrayList<>();
        for (String key : keys) {
            if (key.endsWith("/")) {
                this.collectFolderFilesInto(dto.getBucket(), key, files);
            } else {
                ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadata(dto.getBucket(), key);
                if (metadata != null) {
                    files.add(new ObjectSummaryDto(metadata.getName(), key, false, metadata.getSize(),
                        metadata.getLastModified(), metadata.getEtag(), metadata.getContentType()));
                    this.checkLimits(files);
                }
            }
        }
        if (files.isEmpty()) {
            return new ResponseDto(ERROR, "Nothing to email -- the selected folder(s) are empty.");
        }
        byte[] zipBytes = this.buildZip(dto.getBucket(), files);
        String itemName = String.format("%d selected items", keys.size());
        String sizeLabel = String.format("%s (%d file%s)", formatSize(zipBytes.length), files.size(), files.size() == 1 ? "" : "s");
        String result = this.emailMessagesFactory.sendFileShareEmail(
            dto.getRecipientEmail().trim(), senderName, itemName, "Selection", true,
            sizeLabel, dto.getMessage(), zipBytes, "selected-files.zip", "application/zip");
        return this.toResponseDto(result);
    }

    private ResponseDto emailSingleFile(String bucket, String key, String recipientEmail, String message, String senderName) throws Exception {
        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadata(bucket, key);
        if (isNull(metadata)) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        if (metadata.getSize() != null && metadata.getSize() > MAX_SHARE_BYTES) {
            return new ResponseDto(ERROR, String.format(
                "This file is %s -- too large to email (limit is %s).", formatSize(metadata.getSize()), formatSize(MAX_SHARE_BYTES)));
        }
        ObjectContentDto content = this.storageBrowserService.downloadObject(bucket, key, null, null);
        byte[] bytes;
        try (InputStream in = content.getContent()) {
            bytes = IOUtils.toByteArray(in);
        }
        String result = this.emailMessagesFactory.sendFileShareEmail(
            recipientEmail.trim(), senderName, metadata.getName(), "File", false,
            formatSize(bytes.length), message, bytes, metadata.getName(), content.getContentType());
        return this.toResponseDto(result);
    }

    private ResponseDto emailFolder(String bucket, String folderKey, String recipientEmail, String message, String senderName) throws Exception {
        List<ObjectSummaryDto> files = new ArrayList<>();
        this.collectFolderFilesInto(bucket, folderKey, files);
        if (files.isEmpty()) {
            return new ResponseDto(ERROR, "This folder is empty -- nothing to email.");
        }
        byte[] zipBytes = this.buildZip(bucket, folderKey, files);
        String trimmedKey = folderKey.substring(0, folderKey.length() - 1);
        String folderName = trimmedKey.contains("/") ? trimmedKey.substring(trimmedKey.lastIndexOf('/') + 1) : trimmedKey;
        String sizeLabel = String.format("%s (%d file%s)", formatSize(zipBytes.length), files.size(), files.size() == 1 ? "" : "s");
        String result = this.emailMessagesFactory.sendFileShareEmail(
            recipientEmail.trim(), senderName, folderName, "Folder", true,
            sizeLabel, message, zipBytes, folderName + ".zip", "application/zip");
        return this.toResponseDto(result);
    }

    private void collectFolderFilesInto(String bucket, String folderKey, List<ObjectSummaryDto> files) {
        Deque<String> foldersToVisit = new ArrayDeque<>();
        foldersToVisit.push(folderKey);
        while (!foldersToVisit.isEmpty()) {
            String currentPrefix = foldersToVisit.pop();
            String continuationToken = null;
            do {
                BrowseObjectsResponseDto page = this.storageBrowserService.listObjects(bucket, currentPrefix, continuationToken, 500);
                for (ObjectSummaryDto entry : page.getObjects()) {
                    if (entry.isFolder()) {
                        foldersToVisit.push(entry.getKey());
                        continue;
                    }
                    files.add(entry);
                    this.checkLimits(files);
                }
                continuationToken = page.getNextContinuationToken();
            } while (continuationToken != null);
        }
    }

    private void checkLimits(List<ObjectSummaryDto> files) {
        if (files.size() > MAX_SHARE_FILES) {
            throw new IllegalArgumentException(String.format(
                "This includes more than %d files -- too many to email. Try a smaller selection.", MAX_SHARE_FILES));
        }
        long totalBytes = files.stream().mapToLong(f -> f.getSize() != null ? f.getSize() : 0).sum();
        if (totalBytes > MAX_SHARE_BYTES) {
            throw new IllegalArgumentException(String.format(
                "This is larger than %s combined -- too large to email. Try a smaller selection.", formatSize(MAX_SHARE_BYTES)));
        }
    }

    private byte[] buildZip(String bucket, List<ObjectSummaryDto> files) throws Exception {
        return this.buildZip(bucket, "", files);
    }

    private byte[] buildZip(String bucket, String rootFolderKey, List<ObjectSummaryDto> files) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            for (ObjectSummaryDto file : files) {
                String relativePath = file.getKey().startsWith(rootFolderKey)
                    ? file.getKey().substring(rootFolderKey.length()) : file.getKey();
                zipOutputStream.putNextEntry(new ZipEntry(relativePath));
                ObjectContentDto content = this.storageBrowserService.downloadObject(bucket, file.getKey(), null, null);
                try (InputStream in = content.getContent()) {
                    IOUtils.copy(in, zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    private ResponseDto toResponseDto(String emailFactoryResult) {
        if (emailFactoryResult != null && emailFactoryResult.startsWith("Error")) {
            return new ResponseDto(ERROR, emailFactoryResult);
        }
        return new ResponseDto(SUCCESS, "Email sent.");
    }

    private String formatSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return unitIndex == 0 ? String.format("%.0f %s", size, units[unitIndex]) : String.format("%.1f %s", size, units[unitIndex]);
    }

}
