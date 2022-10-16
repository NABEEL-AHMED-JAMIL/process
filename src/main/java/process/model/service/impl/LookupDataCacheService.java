package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
public class LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheService.class);
    private Map<Long, LookupDataDto> lookupCacheMap = new HashMap<>();

    @Autowired
    private LookupDataRepository lookupDataRepository;

    @PostConstruct
    public void initialize() {
        logger.info("****************Cache-Lookup-Start***************************");
        Iterable<LookupData> lookupDataList = this.lookupDataRepository.findByParentLookupIdIsNull();
        lookupDataList.forEach(lookupData -> {
            if (lookupCacheMap.containsKey(lookupData.getLookupId())) {
                lookupCacheMap.put(lookupData.getLookupId(), getLookupDataDetail(lookupData));
            } else {
                lookupCacheMap.put(lookupData.getLookupId(), getLookupDataDetail(lookupData));
            }
        });
        logger.info(lookupCacheMap.toString());
        logger.info("***************Cache-Lookup-End********************************");
    }

    private LookupDataDto getLookupDataDetail(LookupData lookupData) {
        LookupDataDto parentLookupData =new LookupDataDto();
        parentLookupData.setLookupId(lookupData.getLookupId());
        parentLookupData.setLookupType(lookupData.getLookupType());
        parentLookupData.setLookupValue(lookupData.getLookupValue());
        parentLookupData.setDescription(lookupData.getDescription());
        if (lookupData.getChildren().size() > 0) {
            parentLookupData.setChildren(lookupData.getChildren()
                .stream().map(childLookup -> {
                    LookupDataDto childLookupData =new LookupDataDto();
                    childLookupData.setLookupId(childLookup.getLookupId());
                    childLookupData.setLookupType(childLookup.getLookupType());
                    childLookupData.setLookupValue(childLookup.getLookupValue());
                    childLookupData.setDescription(childLookup.getDescription());
                    return childLookupData;
            }).collect(Collectors.toSet()));
        }
        return parentLookupData;
    }

    public LookupDataDto getParentLookupById(Long lookupId) {
        return this.lookupCacheMap.get(lookupId);
    }

    public LookupDataDto getChildLookupById(Long parentLookupId, Long childLookupId) {
        return this.getParentLookupById(parentLookupId).getChildren().stream().
            filter(childLookup -> childLookupId.equals(childLookup.getLookupId()))
            .findAny().orElse(null);
    }


}
