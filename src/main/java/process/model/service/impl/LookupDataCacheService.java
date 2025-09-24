package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.LookupDataDto;
import process.model.pojo.LookupData;
import process.model.repository.LookupDataRepository;
import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 */
@Service
public class LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheService.class);

    private Map<String, LookupDataDto> lookupCacheMap = new HashMap<>();

    private final LookupDataRepository lookupDataRepository;

    public LookupDataCacheService(LookupDataRepository lookupDataRepository) {
        this.lookupDataRepository = lookupDataRepository;
    }

    @PostConstruct
    public void initialize() {
        logger.info("****************Cache-Lookup-Start***************************");
        Iterable<LookupData> lookupDataList = this.lookupDataRepository.findByParentLookupIdIsNull();
        lookupDataList.forEach(lookupData -> {
            if (this.lookupCacheMap.containsKey(lookupData.getLookupType())) {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            } else {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            }
        });
        //logger.info(lookupCacheMap.toString());
        logger.info("***************Cache-Lookup-End********************************");
    }

    /**
     * Method use to get the lookup data detail
     * @param lookupData
     * @return LookupData
     * */
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

    /**
     * Method use to convert lookup data dto to lookup data
     * @param lookupData
     * @return LookupDataDto
     * */
    private LookupDataDto toLookupDataDto(LookupData lookupData) {
        LookupDataDto lookupDataDto = new LookupDataDto();
        lookupDataDto.setLookupId(lookupData.getLookupId());
        lookupDataDto.setLookupType(lookupData.getLookupType());
        lookupDataDto.setLookupValue(lookupData.getLookupValue());
        lookupDataDto.setDescription(lookupData.getDescription());
        lookupDataDto.setDateCreated(lookupData.getDateCreated());
        return lookupDataDto;
    }
}
