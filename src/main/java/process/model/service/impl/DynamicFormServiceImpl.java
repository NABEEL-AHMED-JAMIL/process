package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.DynamicFormDto;
import process.model.dto.DynamicFormFieldDto;
import process.model.dto.DynamicFormSubmissionDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DynamicForm;
import process.model.pojo.DynamicFormField;
import process.model.pojo.DynamicFormSubmission;
import process.model.repository.DynamicFormFieldRepository;
import process.model.repository.DynamicFormRepository;
import process.model.repository.DynamicFormSubmissionRepository;
import process.model.service.DynamicFormService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

@Service
public class DynamicFormServiceImpl implements DynamicFormService {

    private Logger logger = LoggerFactory.getLogger(DynamicFormServiceImpl.class);

    private static final Set<String> ALLOWED_FIELD_TYPES = new HashSet<>(Arrays.asList(
        "text", "textarea", "number", "email", "password", "url", "tel",
        "date", "time", "month", "color", "select", "multi-select",
        "radio", "checkbox", "toggle", "section"
    ));

    private static final String SECTION_FIELD_TYPE = "section";

    private final Gson gson = new Gson();

    private final DynamicFormRepository dynamicFormRepository;
    private final DynamicFormFieldRepository dynamicFormFieldRepository;
    private final DynamicFormSubmissionRepository dynamicFormSubmissionRepository;
    private final TenantFilterHelper tenantFilterHelper;

    @PersistenceContext
    private EntityManager entityManager;

    public DynamicFormServiceImpl(DynamicFormRepository dynamicFormRepository,
        DynamicFormFieldRepository dynamicFormFieldRepository,
        DynamicFormSubmissionRepository dynamicFormSubmissionRepository,
        TenantFilterHelper tenantFilterHelper) {
        this.dynamicFormRepository = dynamicFormRepository;
        this.dynamicFormFieldRepository = dynamicFormFieldRepository;
        this.dynamicFormSubmissionRepository = dynamicFormSubmissionRepository;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    private boolean isOwnedByCaller(DynamicForm dynamicForm) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return dynamicForm != null && Objects.equals(dynamicForm.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    public ResponseDto addForm(DynamicFormDto dynamicFormDto) throws Exception {
        if (isNull(dynamicFormDto.getFormName()) || dynamicFormDto.getFormName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Form name missing.");
        }
        DynamicForm dynamicForm = new DynamicForm();
        dynamicForm.setTenantId(TenantContext.getTenantId());
        dynamicForm.setFormName(dynamicFormDto.getFormName());
        dynamicForm.setDescription(dynamicFormDto.getDescription());
        dynamicForm.setStatus(Status.Active);
        dynamicForm.setDateCreated(new Timestamp(System.currentTimeMillis()));
        dynamicForm.setUuid(UUID.randomUUID().toString());
        dynamicForm = this.dynamicFormRepository.save(dynamicForm);
        return new ResponseDto(SUCCESS,
            String.format("Form saved with %s.", dynamicForm.getDynamicFormId()),
            this.getDynamicFormDto(dynamicForm, false));
    }

    @Override
    @Transactional
    public ResponseDto updateForm(DynamicFormDto dynamicFormDto) throws Exception {
        if (isNull(dynamicFormDto.getDynamicFormId())) {
            return new ResponseDto(ERROR, "Form dynamicFormId missing.");
        }
        if (isNull(dynamicFormDto.getFormName()) || dynamicFormDto.getFormName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Form name missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findById(dynamicFormDto.getDynamicFormId());
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormDto.getDynamicFormId()));
        }
        DynamicForm dynamicForm = dynamicFormOpt.get();
        dynamicForm.setFormName(dynamicFormDto.getFormName());
        dynamicForm.setDescription(dynamicFormDto.getDescription());
        if (!isNull(dynamicFormDto.getStatus())) {
            dynamicForm.setStatus(dynamicFormDto.getStatus());
        }
        dynamicForm = this.dynamicFormRepository.save(dynamicForm);
        return new ResponseDto(SUCCESS,
            String.format("Form saved with %s.", dynamicForm.getDynamicFormId()),
            this.getDynamicFormDto(dynamicForm, false));
    }

    @Override
    @Transactional
    public ResponseDto deleteForm(Long dynamicFormId) throws Exception {
        if (isNull(dynamicFormId)) {
            return new ResponseDto(ERROR, "Form dynamicFormId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findById(dynamicFormId);
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormId));
        }
        DynamicForm dynamicForm = dynamicFormOpt.get();
        dynamicForm.setStatus(Status.Delete);
        this.dynamicFormRepository.save(dynamicForm);
        return new ResponseDto(SUCCESS, String.format("Form deleted with %s.", dynamicFormId));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllForms() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<DynamicForm> dynamicForms = this.dynamicFormRepository
            .findByStatusNotOrderByDynamicFormIdDesc(Status.Delete);
        List<DynamicFormDto> dynamicFormDtos = dynamicForms.stream()
            .map(this::ensureUuid)
            .map(dynamicForm -> this.getDynamicFormDto(dynamicForm, false))
            .collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Data found.", dynamicFormDtos);
    }

    @Override
    @Transactional
    public ResponseDto fetchFormByFormId(Long dynamicFormId) throws Exception {
        if (isNull(dynamicFormId)) {
            return new ResponseDto(ERROR, "Form dynamicFormId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findById(dynamicFormId);
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getDynamicFormDto(this.ensureUuid(dynamicFormOpt.get()), true));
    }

    @Override
    public ResponseDto fetchFormByUuid(String uuid) throws Exception {
        if (isNull(uuid) || uuid.trim().isEmpty()) {
            return new ResponseDto(ERROR, "Form uuid missing.");
        }
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findByUuid(uuid.trim());
        if (!dynamicFormOpt.isPresent() || dynamicFormOpt.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, String.format("Form not found with uuid %s.", uuid));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getDynamicFormDto(dynamicFormOpt.get(), true));
    }

    private DynamicForm ensureUuid(DynamicForm dynamicForm) {
        if (isNull(dynamicForm.getUuid()) || dynamicForm.getUuid().trim().isEmpty()) {
            dynamicForm.setUuid(UUID.randomUUID().toString());
            dynamicForm = this.dynamicFormRepository.save(dynamicForm);
        }
        return dynamicForm;
    }

    @Override
    @Transactional
    public ResponseDto addField(Long dynamicFormId, DynamicFormFieldDto dynamicFormFieldDto) throws Exception {
        if (isNull(dynamicFormId)) {
            return new ResponseDto(ERROR, "Form dynamicFormId missing.");
        }
        ResponseDto validationError = this.validateField(dynamicFormFieldDto);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findById(dynamicFormId);
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormId));
        }
        DynamicForm dynamicForm = dynamicFormOpt.get();
        DynamicFormField dynamicFormField = this.getDynamicFormField(dynamicFormFieldDto);
        if (isNull(dynamicFormField.getFieldOrder())) {
            dynamicFormField.setFieldOrder(dynamicForm.getFields().size() + 1);
        }
        dynamicForm.getFields().add(dynamicFormField);
        dynamicForm = this.dynamicFormRepository.save(dynamicForm);
        return new ResponseDto(SUCCESS, "Field added.", this.getDynamicFormDto(dynamicForm, true));
    }

    @Override
    @Transactional
    public ResponseDto updateField(DynamicFormFieldDto dynamicFormFieldDto) throws Exception {
        if (isNull(dynamicFormFieldDto.getDynamicFormFieldId())) {
            return new ResponseDto(ERROR, "Field dynamicFormFieldId missing.");
        }
        ResponseDto validationError = this.validateField(dynamicFormFieldDto);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicFormField> dynamicFormFieldOpt = this.dynamicFormFieldRepository
            .findById(dynamicFormFieldDto.getDynamicFormFieldId());
        if (!dynamicFormFieldOpt.isPresent() || !this.isFieldOwnedByCaller(dynamicFormFieldDto.getDynamicFormFieldId())) {
            return new ResponseDto(ERROR, String.format("Field not found with %s.", dynamicFormFieldDto.getDynamicFormFieldId()));
        }
        DynamicFormField dynamicFormField = dynamicFormFieldOpt.get();
        this.applyFieldDto(dynamicFormField, dynamicFormFieldDto);
        this.dynamicFormFieldRepository.save(dynamicFormField);
        return new ResponseDto(SUCCESS, String.format("Field saved with %s.", dynamicFormField.getDynamicFormFieldId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteField(Long dynamicFormFieldId) throws Exception {
        if (isNull(dynamicFormFieldId)) {
            return new ResponseDto(ERROR, "Field dynamicFormFieldId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        if (!this.dynamicFormFieldRepository.existsById(dynamicFormFieldId) || !this.isFieldOwnedByCaller(dynamicFormFieldId)) {
            return new ResponseDto(ERROR, String.format("Field not found with %s.", dynamicFormFieldId));
        }
        this.dynamicFormFieldRepository.deleteById(dynamicFormFieldId);
        return new ResponseDto(SUCCESS, String.format("Field deleted with %s.", dynamicFormFieldId));
    }

    private boolean isFieldOwnedByCaller(Long dynamicFormFieldId) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        Long owningFormId = this.dynamicFormFieldRepository.findOwningFormId(dynamicFormFieldId);
        if (owningFormId == null) {
            return false;
        }
        return this.dynamicFormRepository.findById(owningFormId)
            .map(this::isOwnedByCaller)
            .orElse(false);
    }

    @Override
    @Transactional
    public ResponseDto submitForm(DynamicFormSubmissionDto dynamicFormSubmissionDto) throws Exception {
        if (isNull(dynamicFormSubmissionDto.getDynamicFormId())) {
            return new ResponseDto(ERROR, "Submission dynamicFormId missing.");
        }
        if (isNull(dynamicFormSubmissionDto.getPayload()) || dynamicFormSubmissionDto.getPayload().isEmpty()) {
            return new ResponseDto(ERROR, "Submission payload missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository
            .findById(dynamicFormSubmissionDto.getDynamicFormId());
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormSubmissionDto.getDynamicFormId()));
        }
        DynamicFormSubmission dynamicFormSubmission = new DynamicFormSubmission();
        dynamicFormSubmission.setDynamicFormId(dynamicFormSubmissionDto.getDynamicFormId());
        dynamicFormSubmission.setUuid(UUID.randomUUID().toString());
        dynamicFormSubmission.setPayload(this.gson.toJson(dynamicFormSubmissionDto.getPayload()));
        dynamicFormSubmission.setDateCreated(new Timestamp(System.currentTimeMillis()));
        dynamicFormSubmission = this.dynamicFormSubmissionRepository.save(dynamicFormSubmission);
        return new ResponseDto(SUCCESS,
            String.format("Form submitted with %s.", dynamicFormSubmission.getDynamicFormSubmissionId()),
            this.getDynamicFormSubmissionDto(dynamicFormSubmission));
    }

    @Override
    @Transactional
    public ResponseDto updateSubmission(DynamicFormSubmissionDto dynamicFormSubmissionDto) throws Exception {
        if (isNull(dynamicFormSubmissionDto.getDynamicFormSubmissionId())) {
            return new ResponseDto(ERROR, "Submission dynamicFormSubmissionId missing.");
        }
        if (isNull(dynamicFormSubmissionDto.getPayload()) || dynamicFormSubmissionDto.getPayload().isEmpty()) {
            return new ResponseDto(ERROR, "Submission payload missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicFormSubmission> dynamicFormSubmissionOpt = this.dynamicFormSubmissionRepository
            .findById(dynamicFormSubmissionDto.getDynamicFormSubmissionId());
        if (!dynamicFormSubmissionOpt.isPresent() || !this.isSubmissionOwnedByCaller(dynamicFormSubmissionOpt.get())) {
            return new ResponseDto(ERROR, String.format("Submission not found with %s.", dynamicFormSubmissionDto.getDynamicFormSubmissionId()));
        }
        DynamicFormSubmission dynamicFormSubmission = dynamicFormSubmissionOpt.get();
        dynamicFormSubmission.setPayload(this.gson.toJson(dynamicFormSubmissionDto.getPayload()));
        dynamicFormSubmission = this.dynamicFormSubmissionRepository.save(dynamicFormSubmission);
        return new ResponseDto(SUCCESS,
            String.format("Submission saved with %s.", dynamicFormSubmission.getDynamicFormSubmissionId()),
            this.getDynamicFormSubmissionDto(dynamicFormSubmission));
    }

    @Override
    @Transactional
    public ResponseDto deleteSubmission(Long dynamicFormSubmissionId) throws Exception {
        if (isNull(dynamicFormSubmissionId)) {
            return new ResponseDto(ERROR, "Submission dynamicFormSubmissionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicFormSubmission> dynamicFormSubmissionOpt = this.dynamicFormSubmissionRepository
            .findById(dynamicFormSubmissionId);
        if (!dynamicFormSubmissionOpt.isPresent() || !this.isSubmissionOwnedByCaller(dynamicFormSubmissionOpt.get())) {
            return new ResponseDto(ERROR, String.format("Submission not found with %s.", dynamicFormSubmissionId));
        }
        this.dynamicFormSubmissionRepository.deleteById(dynamicFormSubmissionId);
        return new ResponseDto(SUCCESS, String.format("Submission deleted with %s.", dynamicFormSubmissionId));
    }

    private boolean isSubmissionOwnedByCaller(DynamicFormSubmission dynamicFormSubmission) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        if (dynamicFormSubmission == null || dynamicFormSubmission.getDynamicFormId() == null) {
            return false;
        }
        return this.dynamicFormRepository.findById(dynamicFormSubmission.getDynamicFormId())
            .map(this::isOwnedByCaller)
            .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSubmissionsByFormId(Long dynamicFormId) throws Exception {
        if (isNull(dynamicFormId)) {
            return new ResponseDto(ERROR, "Form dynamicFormId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicForm> dynamicFormOpt = this.dynamicFormRepository.findById(dynamicFormId);
        if (!dynamicFormOpt.isPresent() || !this.isOwnedByCaller(dynamicFormOpt.get())) {
            return new ResponseDto(ERROR, String.format("Form not found with %s.", dynamicFormId));
        }
        List<DynamicFormSubmission> submissions = this.dynamicFormSubmissionRepository
            .findByDynamicFormIdOrderByDynamicFormSubmissionIdDesc(dynamicFormId);
        List<DynamicFormSubmissionDto> submissionDtos = submissions.stream()
            .map(this::getDynamicFormSubmissionDto)
            .collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Data found.", submissionDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSubmissionBySubmissionId(Long dynamicFormSubmissionId) throws Exception {
        if (isNull(dynamicFormSubmissionId)) {
            return new ResponseDto(ERROR, "Submission dynamicFormSubmissionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DynamicFormSubmission> dynamicFormSubmissionOpt = this.dynamicFormSubmissionRepository
            .findById(dynamicFormSubmissionId);
        if (!dynamicFormSubmissionOpt.isPresent() || !this.isSubmissionOwnedByCaller(dynamicFormSubmissionOpt.get())) {
            return new ResponseDto(ERROR, String.format("Submission not found with %s.", dynamicFormSubmissionId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getDynamicFormSubmissionDto(dynamicFormSubmissionOpt.get()));
    }

    @Override
    public ResponseDto fetchSubmissionByUuid(String uuid) throws Exception {
        if (isNull(uuid) || uuid.trim().isEmpty()) {
            return new ResponseDto(ERROR, "Submission uuid missing.");
        }
        Optional<DynamicFormSubmission> dynamicFormSubmissionOpt = this.dynamicFormSubmissionRepository
            .findByUuid(uuid);
        if (!dynamicFormSubmissionOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Submission not found with uuid %s.", uuid));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getDynamicFormSubmissionDto(dynamicFormSubmissionOpt.get()));
    }

    private ResponseDto validateField(DynamicFormFieldDto dynamicFormFieldDto) {
        if (isNull(dynamicFormFieldDto.getFieldType()) || !ALLOWED_FIELD_TYPES.contains(dynamicFormFieldDto.getFieldType())) {
            return new ResponseDto(ERROR, "Field fieldType missing or not supported.");
        }
        if (isNull(dynamicFormFieldDto.getFieldName()) || dynamicFormFieldDto.getFieldName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Field fieldName missing.");
        }
        if (isNull(dynamicFormFieldDto.getFieldLabel()) || dynamicFormFieldDto.getFieldLabel().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Field fieldLabel missing.");
        }
        return null;
    }

    private DynamicFormDto getDynamicFormDto(DynamicForm dynamicForm, boolean includeFields) {
        DynamicFormDto dto = new DynamicFormDto();
        dto.setDynamicFormId(dynamicForm.getDynamicFormId());
        dto.setFormName(dynamicForm.getFormName());
        dto.setDescription(dynamicForm.getDescription());
        dto.setStatus(dynamicForm.getStatus());
        dto.setDateCreated(dynamicForm.getDateCreated());
        dto.setUuid(dynamicForm.getUuid());
        dto.setTotalFields(dynamicForm.getFields().size());
        if (includeFields) {
            dto.setFields(dynamicForm.getFields().stream()
                .map(this::getDynamicFormFieldDto)
                .collect(Collectors.toList()));
        }
        return dto;
    }

    private DynamicFormFieldDto getDynamicFormFieldDto(DynamicFormField dynamicFormField) {
        DynamicFormFieldDto dto = new DynamicFormFieldDto();
        dto.setDynamicFormFieldId(dynamicFormField.getDynamicFormFieldId());
        dto.setFieldOrder(dynamicFormField.getFieldOrder());
        dto.setFieldType(dynamicFormField.getFieldType());
        dto.setFieldName(dynamicFormField.getFieldName());
        dto.setFieldLabel(dynamicFormField.getFieldLabel());
        dto.setPlaceHolder(dynamicFormField.getPlaceHolder());
        dto.setDefaultValue(dynamicFormField.getDefaultValue());
        dto.setMandatory(dynamicFormField.isMandatory());
        dto.setPattern(dynamicFormField.getPattern());
        dto.setMinLength(dynamicFormField.getMinLength());
        dto.setMaxLength(dynamicFormField.getMaxLength());
        dto.setFieldWidth(dynamicFormField.getFieldWidth());
        dto.setFieldOptions(dynamicFormField.getFieldOptions());
        return dto;
    }

    private DynamicFormField getDynamicFormField(DynamicFormFieldDto dto) {
        DynamicFormField dynamicFormField = new DynamicFormField();
        this.applyFieldDto(dynamicFormField, dto);
        return dynamicFormField;
    }

    private void applyFieldDto(DynamicFormField dynamicFormField, DynamicFormFieldDto dto) {
        dynamicFormField.setFieldOrder(dto.getFieldOrder());
        dynamicFormField.setFieldType(dto.getFieldType());
        dynamicFormField.setFieldName(dto.getFieldName());
        dynamicFormField.setFieldLabel(dto.getFieldLabel());
        dynamicFormField.setPlaceHolder(dto.getPlaceHolder());
        dynamicFormField.setDefaultValue(dto.getDefaultValue());
        dynamicFormField.setMandatory(SECTION_FIELD_TYPE.equals(dto.getFieldType()) ? false : dto.isMandatory());
        dynamicFormField.setPattern(dto.getPattern());
        dynamicFormField.setMinLength(dto.getMinLength());
        dynamicFormField.setMaxLength(dto.getMaxLength());
        dynamicFormField.setFieldWidth(isNull(dto.getFieldWidth()) ? 12 : dto.getFieldWidth());
        dynamicFormField.setFieldOptions(dto.getFieldOptions());
    }

    private DynamicFormSubmissionDto getDynamicFormSubmissionDto(DynamicFormSubmission dynamicFormSubmission) {
        DynamicFormSubmissionDto dto = new DynamicFormSubmissionDto();
        dto.setDynamicFormSubmissionId(dynamicFormSubmission.getDynamicFormSubmissionId());
        dto.setDynamicFormId(dynamicFormSubmission.getDynamicFormId());
        dto.setUuid(dynamicFormSubmission.getUuid());
        dto.setDateCreated(dynamicFormSubmission.getDateCreated());
        if (!isNull(dynamicFormSubmission.getPayload())) {
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            dto.setPayload(this.gson.fromJson(dynamicFormSubmission.getPayload(), mapType));
        }
        return dto;
    }

}
