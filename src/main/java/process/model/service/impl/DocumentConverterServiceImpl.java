package process.model.service.impl;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.DocumentConverterTaskDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DocumentConverterTask;
import process.model.repository.DocumentConverterTaskRepository;
import process.model.service.DocumentConverterService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TenantOwnership;
import process.util.ContentTypeUtil;
import process.util.DocumentConverterFormatRegistry;
import process.util.MarkdownDocumentFormat;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class DocumentConverterServiceImpl implements DocumentConverterService {

    private Logger logger = LoggerFactory.getLogger(DocumentConverterServiceImpl.class);

    // The converted bytes are base64-encoded into the JSON response, so a conversion holds
    // roughly four times the file size in heap at once (input + output + base64 string +
    // the serialised response). The global multipart limit is 500MB, which at that ratio
    // would exhaust the heap on a single request -- this is the converter's own, much
    // lower ceiling, with a message that says what to do instead.
    @Value("${document.converter.max-file-size-mb:50}")
    private int maxFileSizeMb;

    private final DocumentConverterTaskRepository documentConverterTaskRepository;
    private final StorageBrowserService storageBrowserService;
    private final TenantFilterHelper tenantFilterHelper;
    private final DocumentConverter documentConverter;
    private final DocumentFormatRegistry documentFormatRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    public DocumentConverterServiceImpl(DocumentConverterTaskRepository documentConverterTaskRepository,
        StorageBrowserService storageBrowserService,
        TenantFilterHelper tenantFilterHelper,
        DocumentConverter documentConverter,
        DocumentFormatRegistry documentFormatRegistry) {
        this.documentConverterTaskRepository = documentConverterTaskRepository;
        this.storageBrowserService = storageBrowserService;
        this.tenantFilterHelper = tenantFilterHelper;
        this.documentConverter = documentConverter;
        this.documentFormatRegistry = documentFormatRegistry;
    }

    private boolean isOwnedByCaller(DocumentConverterTask documentConverterTask) {
        return documentConverterTask != null && TenantOwnership.isOwnedByCaller(documentConverterTask.getTenantId());
    }

    @Override
    public ResponseDto supportedFormats() throws Exception {
        return new ResponseDto(SUCCESS, "Supported formats fetched successfully.",
            DocumentConverterFormatRegistry.allFamilies().values());
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllTasks() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<DocumentConverterTask> tasks = this.documentConverterTaskRepository
            .findByStatusNotOrderByDocumentConverterTaskIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", tasks);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchTaskById(Long documentConverterTaskId) throws Exception {
        if (isNull(documentConverterTaskId)) {
            return new ResponseDto(ERROR, "DocumentConverterTask documentConverterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DocumentConverterTask> task = this.documentConverterTaskRepository.findById(documentConverterTaskId);
        if (task.isPresent() && this.isOwnedByCaller(task.get())) {
            return new ResponseDto(SUCCESS, "Data fetched successfully.", task.get());
        }
        return new ResponseDto(ERROR, String.format("DocumentConverterTask not found with %s.", documentConverterTaskId));
    }

    @Override
    @Transactional
    public ResponseDto convert(MultipartFile file, String outputFormat, String bucketName, String targetFolder, String taskName, boolean save) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "Uploaded file is empty.");
        }
        long maxBytes = (long) this.maxFileSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            return new ResponseDto(ERROR, String.format(
                "That file is %.1f MB, over the %d MB conversion limit. Split it, or convert it outside the app.",
                file.getSize() / (1024d * 1024d), this.maxFileSizeMb));
        }
        if (isNull(outputFormat) || outputFormat.trim().isEmpty()) {
            return new ResponseDto(ERROR, "outputFormat missing.");
        }

        String safeFileName = Paths.get(file.getOriginalFilename()).getFileName().toString();
        String inputExtension = ContentTypeUtil.extensionOf(safeFileName);
        String normalizedOutputFormat = outputFormat.trim().toLowerCase();
        DocumentConverterFormatRegistry.FormatFamily family = DocumentConverterFormatRegistry.familyOfInput(inputExtension);
        if (family == null) {
            return new ResponseDto(ERROR, String.format("'%s' isn't a supported input format.", inputExtension));
        }
        if (!family.getOutputFormats().contains(normalizedOutputFormat)) {
            return new ResponseDto(ERROR, String.format(
                "Can't convert a %s to '%s' -- valid targets are: %s.",
                family.getLabel(), normalizedOutputFormat, String.join(", ", family.getOutputFormats())));
        }
        if (save) {
            if (isNull(bucketName) || bucketName.trim().isEmpty()) {
                return new ResponseDto(ERROR, "bucketName is required to save this conversion.");
            }
            if (isNull(taskName) || taskName.trim().isEmpty()) {
                return new ResponseDto(ERROR, "taskName is required to save this conversion.");
            }
        }

        DocumentFormat sourceFormat = this.resolveFormat(inputExtension);
        DocumentFormat targetFormat = this.resolveFormat(normalizedOutputFormat);
        if (sourceFormat == null || targetFormat == null) {

            logger.error("DocumentConverterFormatRegistry allowed {} -> {} but JODConverter's own registry doesn't recognize one of them.",
                inputExtension, normalizedOutputFormat);
            return new ResponseDto(ERROR, "That format isn't available right now -- please try a different target format.");
        }

        String baseFileName = this.baseNameOf(safeFileName);
        String outputFileName = baseFileName + "." + normalizedOutputFormat;
        String outputContentType = ContentTypeUtil.contentTypeFor(outputFileName);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Path tempDir = Files.createTempDirectory("doc-converter-");
        Path tempSourceFile = tempDir.resolve(safeFileName);
        try {

            try (InputStream sourceInputStream = file.getInputStream()) {
                Files.copy(sourceInputStream, tempSourceFile, StandardCopyOption.REPLACE_EXISTING);
            }
            this.documentConverter.convert(tempSourceFile.toFile()).as(sourceFormat).to(outputStream).as(targetFormat).execute();
        } catch (Exception conversionException) {
            logger.warn("Document conversion failed for {} -> {}: {}", safeFileName, normalizedOutputFormat,
                conversionException.getMessage());
            String detail = conversionException.getMessage() == null ? "" : conversionException.getMessage();
            if (detail.toLowerCase().contains("timeout") || detail.toLowerCase().contains("timed out")) {
                return new ResponseDto(ERROR, "Conversion timed out. Conversions run one at a time, so a large "
                    + "document already being converted can hold up the queue -- try again shortly.");
            }
            return new ResponseDto(ERROR, "Conversion failed -- the file may be corrupt or password-protected: "
                + detail);
        } finally {
            try {
                Files.deleteIfExists(tempSourceFile);
                Files.deleteIfExists(tempDir);
            } catch (Exception cleanupException) {
                logger.warn("Failed to clean up temp conversion file {}: {}", tempSourceFile, cleanupException.getMessage());
            }
        }
        byte[] outputBytes = outputStream.toByteArray();

        DocumentConverterTaskDto response = new DocumentConverterTaskDto();
        response.setInputFileName(safeFileName);
        response.setInputFormat(inputExtension);
        response.setInputContentType(file.getContentType());
        response.setInputFileSize(file.getSize());
        response.setOutputFormat(normalizedOutputFormat);
        response.setOutputFileName(outputFileName);
        response.setOutputContentType(outputContentType);
        response.setOutputFileSize((long) outputBytes.length);
        response.setOutputBase64(Base64.getEncoder().encodeToString(outputBytes));
        response.setSave(save);

        if (save) {
            String folder = this.normalizeTargetFolder(targetFolder);

            DocumentConverterTask task = new DocumentConverterTask();
            task.setTenantId(TenantContext.getTenantId());
            task.setTaskName(taskName.trim());
            task.setInputFileName(safeFileName);
            task.setInputFormat(inputExtension);
            task.setInputContentType(file.getContentType());
            task.setInputFileSize(file.getSize());
            task.setOutputFormat(normalizedOutputFormat);
            task.setOutputFileName(outputFileName);
            task.setOutputContentType(outputContentType);
            task.setOutputFileSize((long) outputBytes.length);
            task.setBucketName(bucketName.trim());
            task.setTargetFolder(folder);

            task.setInputStorageKey("pending");
            task.setOutputStorageKey("pending");
            task.setStatus(Status.Active);
            this.documentConverterTaskRepository.save(task);

            String prefix = folder + "/" + task.getDocumentConverterTaskId() + "/";
            String inputKey = prefix + "input/" + safeFileName;
            String outputKey = prefix + "output/" + outputFileName;
            this.storageBrowserService.uploadObject(bucketName.trim(), inputKey,
                file.getInputStream(), file.getSize(), file.getContentType());
            this.storageBrowserService.uploadObject(bucketName.trim(), outputKey,
                new ByteArrayInputStream(outputBytes), outputBytes.length, outputContentType);

            task.setInputStorageKey(inputKey);
            task.setOutputStorageKey(outputKey);
            this.documentConverterTaskRepository.save(task);

            response.setDocumentConverterTaskId(task.getDocumentConverterTaskId());
            response.setBucketName(task.getBucketName());
            response.setTargetFolder(folder);
            response.setInputStorageKey(inputKey);
            response.setOutputStorageKey(outputKey);
            response.setStatus(task.getStatus());
            response.setDateCreated(task.getDateCreated());
        }
        return new ResponseDto(SUCCESS, "Document converted successfully.", response);
    }

    /**
     * JODConverter's registry has no Markdown entry, so getFormatByExtension("md") returns null
     * and the conversion is rejected before LibreOffice ever sees it -- even though LibreOffice
     * 26.2 handles Markdown in both directions. Fall back to our own definition for that one
     * extension and leave every other format resolving exactly as before.
     */
    private DocumentFormat resolveFormat(String extension) {
        if (MarkdownDocumentFormat.isMarkdown(extension)) {
            return MarkdownDocumentFormat.get();
        }
        return this.documentFormatRegistry.getFormatByExtension(extension);
    }

    private String normalizeTargetFolder(String targetFolder) {
        if (isNull(targetFolder) || targetFolder.trim().isEmpty()) {
            return "document-converter";
        }
        String folder = targetFolder.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return folder.isEmpty() ? "document-converter" : folder;
    }

    @Override
    @Transactional
    public ResponseDto deleteTask(Long documentConverterTaskId) throws Exception {
        if (isNull(documentConverterTaskId)) {
            return new ResponseDto(ERROR, "DocumentConverterTask documentConverterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DocumentConverterTask> task = this.documentConverterTaskRepository.findById(documentConverterTaskId);
        if (task.isPresent() && !this.isOwnedByCaller(task.get())) {
            return new ResponseDto(ERROR, String.format("DocumentConverterTask not found with %s.", documentConverterTaskId));
        }
        // The absent case used to fall straight through to the SUCCESS below, so a delete that
        // deleted nothing was indistinguishable from one that worked: the user saw a success
        // toast and found the row still there on the next refresh, with nothing to explain it.
        // Same wording fetchTaskById already uses, so a missing row reads the same either way.
        if (!task.isPresent()) {
            return new ResponseDto(ERROR, String.format("DocumentConverterTask not found with %s.", documentConverterTaskId));
        }
        DocumentConverterTask documentConverterTask = task.get();
        documentConverterTask.setStatus(Status.Delete);
        this.documentConverterTaskRepository.save(documentConverterTask);
        return this.retainedObjectsResponse(documentConverterTask);
    }

    /**
     * The two objects convert() wrote are deliberately NOT removed here, and this is the record of
     * that decision.
     *
     * The obvious fix for "a deleted task orphans its files" is to delete them on the way past, but
     * the confirmation the caller accepts before this request says, in as many words, "The converted
     * file stays in the bucket", and both keys live in a bucket and a folder the caller chose for
     * themselves in convert() -- next to their own files, not in a private scratch area. Removing an
     * object cannot be undone, so a delete that quietly destroyed the converted output would be data
     * loss against the promise the UI had just made: worse than the leak, and unrecoverable when it
     * turns out to be wrong. The row itself is only soft-deleted for the same reason.
     *
     * What was genuinely broken is that the delete then forgot where the files went. fetchAllTasks
     * filters Status.Delete out, so the one record still holding inputStorageKey/outputStorageKey
     * vanished from the app the moment it was set, and the leftovers could only be found by someone
     * who already knew the prefix convert() had built. So the keys are handed back on the delete
     * response and logged as retained: the objects stay findable in the object browser, an operator
     * can see what is holding space, and the delete stays reversible in both directions -- the row
     * can be flipped back to Active and the bytes it points at are still there.
     *
     * Reclaiming the space is therefore a separate, deliberate act (delete the folder in the object
     * browser). That is the trade this makes on purpose; it is not an oversight.
     */
    private ResponseDto retainedObjectsResponse(DocumentConverterTask documentConverterTask) {
        logger.warn("DocumentConverterTask {} deleted, its objects kept on purpose: bucket={} input={} output={} -- "
            + "remove them in the object browser to reclaim the space.", documentConverterTask.getDocumentConverterTaskId(),
            documentConverterTask.getBucketName(), documentConverterTask.getInputStorageKey(),
            documentConverterTask.getOutputStorageKey());

        DocumentConverterTaskDto retained = new DocumentConverterTaskDto();
        retained.setDocumentConverterTaskId(documentConverterTask.getDocumentConverterTaskId());
        retained.setTaskName(documentConverterTask.getTaskName());
        retained.setInputFileName(documentConverterTask.getInputFileName());
        retained.setOutputFileName(documentConverterTask.getOutputFileName());
        retained.setBucketName(documentConverterTask.getBucketName());
        retained.setTargetFolder(documentConverterTask.getTargetFolder());
        retained.setInputStorageKey(documentConverterTask.getInputStorageKey());
        retained.setOutputStorageKey(documentConverterTask.getOutputStorageKey());
        retained.setStatus(documentConverterTask.getStatus());

        String retainedPrefix = this.retainedPrefixOf(documentConverterTask.getInputStorageKey(),
            documentConverterTask.getOutputStorageKey());
        String location = retainedPrefix.isEmpty()
            ? String.format("'%s' and '%s'", documentConverterTask.getInputStorageKey(),
                documentConverterTask.getOutputStorageKey())
            : String.format("'%s'", retainedPrefix);
        return new ResponseDto(SUCCESS, String.format(
            "DocumentConverterTask deleted with %s. Its input and output files are still in bucket '%s' under %s -- "
                + "delete them there if you no longer need them.",
            documentConverterTask.getDocumentConverterTaskId(), documentConverterTask.getBucketName(), location), retained);
    }

    /**
     * convert() writes the pair as "folder/taskId/input|output/fileName", so the folder they share
     * is the one thing a user can paste into the object browser to see everything the task left
     * behind. Derived from the keys actually stored rather than rebuilt from targetFolder, because a
     * row written before a folder rule changed would otherwise be pointed at a prefix that does not
     * exist. A row whose keys share no folder at all -- the "pending" placeholders convert() writes
     * before the upload -- yields an empty prefix, and the caller names both keys instead.
     */
    private String retainedPrefixOf(String inputStorageKey, String outputStorageKey) {
        if (isNull(inputStorageKey) || isNull(outputStorageKey)) {
            return "";
        }
        int shared = 0;
        int limit = Math.min(inputStorageKey.length(), outputStorageKey.length());
        while (shared < limit && inputStorageKey.charAt(shared) == outputStorageKey.charAt(shared)) {
            shared++;
        }
        String common = inputStorageKey.substring(0, shared);
        int lastSlash = common.lastIndexOf('/');
        return lastSlash >= 0 ? common.substring(0, lastSlash + 1) : "";
    }

    private String baseNameOf(String fileName) {
        if (isNull(fileName)) {
            return "converted";
        }
        String name = new File(fileName).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

}
