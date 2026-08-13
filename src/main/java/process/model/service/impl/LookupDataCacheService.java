package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.LookupDataDto;
import process.model.pojo.LookupData;
import process.model.repository.LookupDataRepository;
import process.util.EncryptionUtil;
import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheService.class);

    private Map<String, LookupDataDto> lookupCacheMap = new HashMap<>();

    private final LookupDataRepository lookupDataRepository;
    private final EncryptionUtil encryptionUtil;

    public LookupDataCacheService(LookupDataRepository lookupDataRepository, EncryptionUtil encryptionUtil) {
        this.lookupDataRepository = lookupDataRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @PostConstruct
    public void initialize() {
        try {
            initializeCache();
        } catch (Exception ex) {
            logger.error("Failed to initialize lookup cache: {}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void initializeCache() {
        logger.info("****************Cache-Lookup-Start***************************");
        Iterable<LookupData> lookupDataList = this.lookupDataRepository.findByParentLookupIdIsNull();
        lookupDataList.forEach(lookupData -> {
            if (this.lookupCacheMap.containsKey(lookupData.getLookupType())) {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            } else {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            }
        });

        logger.info("***************Cache-Lookup-End********************************");
    }

    private LookupDataDto getLookupDataDetail(LookupData lookupData) {
        LookupDataDto parentLookupData = this.toLookupDataDto(lookupData);
        if (!lookupData.getChildren().isEmpty()) {
            parentLookupData.setChildren(lookupData.getChildren()
                .stream().map(this::toLookupDataDto)
                .collect(Collectors.toSet()));
        }
        return parentLookupData;
    }

    public LookupDataDto getParentLookupById(String lookupType) {
        return this.lookupCacheMap.get(lookupType);
    }

    public Map<String, LookupDataDto> getLookupCacheMap() {
        return lookupCacheMap;
    }

    private LookupDataDto toLookupDataDto(LookupData lookupData) {
        LookupDataDto lookupDataDto = new LookupDataDto();
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setEncrypted(lookupData.getEncrypted());
        lookupDataDto.setTenantId(lookupData.getTenantId());
        lookupDataDto.setLookupValue(Boolean.TRUE.equals(lookupData.getEncrypted())
            ? this.encryptionUtil.decrypt(lookupData.getLookupValue())
            : lookupData.getLookupValue());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
        return lookupDataDto;
    }
}
