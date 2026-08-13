package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ObjectContentDto;
import process.model.dto.PdfHighlighterFieldDto;
import process.model.dto.PdfHighlighterTaskDto;
import process.model.dto.ResponseDto;
import process.model.dto.SyncPdfHighlighterFieldsRequestDto;
import process.model.enums.HighlighterStatus;
import process.model.enums.Status;
import process.model.pojo.PdfHighlighterField;
import process.model.pojo.PdfHighlighterTask;
import process.model.repository.PdfHighlighterFieldRepository;
import process.model.repository.PdfHighlighterTaskRepository;
import process.model.service.PdfHighlighterTaskService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import static process.util.ProcessUtil.*;

@Service
public class PdfHighlighterTaskServiceImpl implements PdfHighlighterTaskService {

    private Logger logger = LoggerFactory.getLogger(PdfHighlighterTaskServiceImpl.class);

    private static final String BUCKET = "etl-bucket";

    private final PdfHighlighterTaskRepository pdfHighlighterTaskRepository;
    private final PdfHighlighterFieldRepository pdfHighlighterFieldRepository;
    private final StorageBrowserService storageBrowserService;
    private final TenantFilterHelper tenantFilterHelper;

    @PersistenceContext
    private EntityManager entityManager;

    public PdfHighlighterTaskServiceImpl(PdfHighlighterTaskRepository pdfHighlighterTaskRepository,
        PdfHighlighterFieldRepository pdfHighlighterFieldRepository,
        StorageBrowserService storageBrowserService,
        TenantFilterHelper tenantFilterHelper) {
        this.pdfHighlighterTaskRepository = pdfHighlighterTaskRepository;
        this.pdfHighlighterFieldRepository = pdfHighlighterFieldRepository;
        this.storageBrowserService = storageBrowserService;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    private boolean isOwnedByCaller(PdfHighlighterTask pdfHighlighterTask) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return pdfHighlighterTask != null && Objects.equals(pdfHighlighterTask.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllPdfHighlighterTask() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<PdfHighlighterTask> pdfHighlighterTasks = this.pdfHighlighterTaskRepository
            .findByStatusNotOrderByPdfHighlighterTaskIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data fetch successfully.", pdfHighlighterTasks);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchPdfHighlighterTaskById(Long pdfHighlighterTaskId) throws Exception {
        if (isNull(pdfHighlighterTaskId)) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<PdfHighlighterTask> pdfHighlighterTask = this.pdfHighlighterTaskRepository.findById(pdfHighlighterTaskId);
        if (pdfHighlighterTask.isPresent() && this.isOwnedByCaller(pdfHighlighterTask.get())) {
            return new ResponseDto(SUCCESS, "Data fetch successfully.", pdfHighlighterTask.get());
        }
        return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskId));
    }

    @Override
    @Transactional
    public ResponseDto addPdfHighlighterTask(PdfHighlighterTaskDto pdfHighlighterTaskDto) throws Exception {
        if (isNull(pdfHighlighterTaskDto.getTaskName()) || pdfHighlighterTaskDto.getTaskName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "PdfHighlighterTask taskName missing.");
        }
        PdfHighlighterTask pdfHighlighterTask = new PdfHighlighterTask();
        pdfHighlighterTask.setTenantId(TenantContext.getTenantId());
        pdfHighlighterTask.setTaskName(pdfHighlighterTaskDto.getTaskName());
        pdfHighlighterTask.setDescription(pdfHighlighterTaskDto.getDescription());
        pdfHighlighterTask.setHighlighterStatus(!isNull(pdfHighlighterTaskDto.getHighlighterStatus())
            ? pdfHighlighterTaskDto.getHighlighterStatus() : HighlighterStatus.Draft);
        pdfHighlighterTask.setStatus(Status.Active);
        this.pdfHighlighterTaskRepository.save(pdfHighlighterTask);
        return new ResponseDto(SUCCESS, String.format("PdfHighlighterTask save with %s.", pdfHighlighterTask.getPdfHighlighterTaskId()),
            pdfHighlighterTask);
    }

    @Override
    @Transactional
    public ResponseDto updatePdfHighlighterTask(PdfHighlighterTaskDto pdfHighlighterTaskDto) throws Exception {
        if (isNull(pdfHighlighterTaskDto.getPdfHighlighterTaskId())) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        } else if (isNull(pdfHighlighterTaskDto.getTaskName()) || pdfHighlighterTaskDto.getTaskName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "PdfHighlighterTask taskName missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<PdfHighlighterTask> pdfHighlighterTask = this.pdfHighlighterTaskRepository
            .findById(pdfHighlighterTaskDto.getPdfHighlighterTaskId());
        if (pdfHighlighterTask.isPresent() && !this.isOwnedByCaller(pdfHighlighterTask.get())) {
            return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskDto.getPdfHighlighterTaskId()));
        }
        if (pdfHighlighterTask.isPresent()) {
            pdfHighlighterTask.get().setTaskName(pdfHighlighterTaskDto.getTaskName());
            pdfHighlighterTask.get().setDescription(pdfHighlighterTaskDto.getDescription());
            if (!isNull(pdfHighlighterTaskDto.getHighlighterStatus())) {
                pdfHighlighterTask.get().setHighlighterStatus(pdfHighlighterTaskDto.getHighlighterStatus());
            }
            if (!isNull(pdfHighlighterTaskDto.getStatus())) {
                pdfHighlighterTask.get().setStatus(pdfHighlighterTaskDto.getStatus());
            }
            this.pdfHighlighterTaskRepository.save(pdfHighlighterTask.get());
            return new ResponseDto(SUCCESS, String.format("PdfHighlighterTask save with %s.", pdfHighlighterTaskDto.getPdfHighlighterTaskId()));
        }
        return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskDto.getPdfHighlighterTaskId()));
    }

    @Override
    @Transactional
    public ResponseDto deletePdfHighlighterTask(Long pdfHighlighterTaskId) throws Exception {
        if (isNull(pdfHighlighterTaskId)) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<PdfHighlighterTask> pdfHighlighterTask = this.pdfHighlighterTaskRepository.findById(pdfHighlighterTaskId);
        if (pdfHighlighterTask.isPresent() && !this.isOwnedByCaller(pdfHighlighterTask.get())) {
            return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskId));
        }
        if (pdfHighlighterTask.isPresent()) {
            pdfHighlighterTask.get().setStatus(Status.Delete);
            this.pdfHighlighterTaskRepository.save(pdfHighlighterTask.get());
        }
        return new ResponseDto(SUCCESS, String.format("PdfHighlighterTask delete with %s.", pdfHighlighterTaskId));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchPdfHighlighterFields(Long pdfHighlighterTaskId) throws Exception {
        if (isNull(pdfHighlighterTaskId)) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        Optional<PdfHighlighterTask> taskOpt = this.pdfHighlighterTaskRepository.findById(pdfHighlighterTaskId);
        if (!taskOpt.isPresent() || !this.isOwnedByCaller(taskOpt.get())) {
            return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskId));
        }
        List<PdfHighlighterField> fields = this.pdfHighlighterFieldRepository
            .findByPdfHighlighterTaskIdOrderByDisplayOrderAsc(pdfHighlighterTaskId);
        return new ResponseDto(SUCCESS, "Data fetch successfully.", fields);
    }

    @Override
    @Transactional
    public ResponseDto syncPdfHighlighterFields(SyncPdfHighlighterFieldsRequestDto request) throws Exception {
        if (isNull(request.getPdfHighlighterTaskId())) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<PdfHighlighterTask> ownerTaskOpt = this.pdfHighlighterTaskRepository.findById(request.getPdfHighlighterTaskId());
        if (!ownerTaskOpt.isPresent() || !this.isOwnedByCaller(ownerTaskOpt.get())) {
            return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", request.getPdfHighlighterTaskId()));
        }
        this.pdfHighlighterFieldRepository.deleteByPdfHighlighterTaskId(request.getPdfHighlighterTaskId());
        List<PdfHighlighterFieldDto> incoming = request.getFields() != null ? request.getFields() : new ArrayList<>();
        List<PdfHighlighterField> toSave = new ArrayList<>();
        int order = 0;
        for (PdfHighlighterFieldDto dto : incoming) {
            PdfHighlighterField field = new PdfHighlighterField();
            field.setPdfHighlighterTaskId(request.getPdfHighlighterTaskId());
            field.setLabel(dto.getLabel());
            field.setPage(dto.getPage());
            field.setX(dto.getX());
            field.setY(dto.getY());
            field.setWidth(dto.getWidth());
            field.setHeight(dto.getHeight());
            field.setDisplayOrder(!isNull(dto.getDisplayOrder()) ? dto.getDisplayOrder() : order);
            field.setSelectorPath(dto.getSelectorPath());
            field.setSelectorText(dto.getSelectorText());
            field.setSelectorPrefix(dto.getSelectorPrefix());
            field.setSelectorSuffix(dto.getSelectorSuffix());
            field.setUseXpathFirst(!isNull(dto.getUseXpathFirst()) ? dto.getUseXpathFirst() : false);
            toSave.add(field);
            order++;
        }
        this.pdfHighlighterFieldRepository.saveAll(toSave);
        return new ResponseDto(SUCCESS, String.format("%d field(s) saved for PdfHighlighterTask %s.", toSave.size(), request.getPdfHighlighterTaskId()));
    }

    @Override
    @Transactional
    public ResponseDto uploadPdfHighlighterFile(Long pdfHighlighterTaskId, MultipartFile file) throws Exception {
        if (isNull(pdfHighlighterTaskId)) {
            return new ResponseDto(ERROR, "PdfHighlighterTask pdfHighlighterTaskId missing.");
        }
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "Uploaded file is empty.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<PdfHighlighterTask> pdfHighlighterTask = this.pdfHighlighterTaskRepository.findById(pdfHighlighterTaskId);
        if (!pdfHighlighterTask.isPresent() || !this.isOwnedByCaller(pdfHighlighterTask.get())) {
            return new ResponseDto(ERROR, String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskId));
        }
        String safeFileName = Paths.get(file.getOriginalFilename()).getFileName().toString();
        this.storageBrowserService.uploadObject(BUCKET, this.taskPrefix(pdfHighlighterTaskId), file);
        pdfHighlighterTask.get().setFileName(safeFileName);
        pdfHighlighterTask.get().setFileSize(file.getSize());
        pdfHighlighterTask.get().setFileContentType(file.getContentType());
        this.pdfHighlighterTaskRepository.save(pdfHighlighterTask.get());
        return new ResponseDto(SUCCESS, "File uploaded successfully.", pdfHighlighterTask.get());
    }

    @Override
    @Transactional(readOnly = true)
    public ObjectContentDto downloadPdfHighlighterFile(Long pdfHighlighterTaskId) throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        PdfHighlighterTask pdfHighlighterTask = this.pdfHighlighterTaskRepository.findById(pdfHighlighterTaskId)
            .filter(this::isOwnedByCaller)
            .orElseThrow(() -> new IllegalArgumentException(String.format("PdfHighlighterTask not found with %s.", pdfHighlighterTaskId)));
        if (isNull(pdfHighlighterTask.getFileName())) {
            throw new IllegalStateException("No file uploaded for this task yet.");
        }
        String key = this.taskPrefix(pdfHighlighterTaskId) + pdfHighlighterTask.getFileName();
        return this.storageBrowserService.downloadObject(BUCKET, key, null, null);
    }

    private String taskPrefix(Long pdfHighlighterTaskId) {
        return "pdf-highlighter/" + pdfHighlighterTaskId + "/";
    }

}
