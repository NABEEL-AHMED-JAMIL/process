package process.model.service.impl;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import process.util.ContentTypeUtil;
import process.util.DocumentConverterFormatRegistry;
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
import java.util.Objects;
import java.util.Optional;
import static process.util.ProcessUtil.*;

@Service
public class DocumentConverterServiceImpl implements DocumentConverterService {

    private Logger logger = LoggerFactory.getLogger(DocumentConverterServiceImpl.class);

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
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return documentConverterTask != null && Objects.equals(documentConverterTask.getTenantId(), TenantContext.getTenantId());
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

        DocumentFormat sourceFormat = this.documentFormatRegistry.getFormatByExtension(inputExtension);
        DocumentFormat targetFormat = this.documentFormatRegistry.getFormatByExtension(normalizedOutputFormat);
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
            return new ResponseDto(ERROR, "Conversion failed -- the file may be corrupt or password-protected: "
                + conversionException.getMessage());
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
        if (task.isPresent()) {
            task.get().setStatus(Status.Delete);
            this.documentConverterTaskRepository.save(task.get());
        }
        return new ResponseDto(SUCCESS, String.format("DocumentConverterTask deleted with %s.", documentConverterTaskId));
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
