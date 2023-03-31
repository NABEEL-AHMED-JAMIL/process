package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.model.pojo.LookupData;
import process.model.repository.AppUserRepository;
import process.model.repository.LookupDataRepository;
import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.payload.response.LookupDataResponse;
import process.service.LookupDataCacheService;
import process.util.ProcessUtil;
import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class LookupDataCacheServiceImpl implements LookupDataCacheService {

    private Logger logger = LoggerFactory.getLogger(LookupDataCacheServiceImpl.class);

    private final String PARENT_LOOKUP_DATA = "parentLookupData";
    private final String LOOKUP_DATA = "lookupData";

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;

    private Map<String, LookupDataResponse> lookupCacheMap = new HashMap<>();

    @PostConstruct
    public void initialize() {
        logger.info("****************Cache-Lookup-Start***************************");
        Iterable<LookupData> lookupDataList = this.lookupDataRepository.findByParentLookupIsNull();
        lookupDataList.forEach(lookupData -> {
            if (this.lookupCacheMap.containsKey(lookupData.getLookupType())) {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            } else {
                this.lookupCacheMap.put(lookupData.getLookupType(), getLookupDataDetail(lookupData));
            }
        });
        logger.info("***************Cache-Lookup-End********************************");
    }

    @Override
    public AppResponse addLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        if (isNull(lookupDataRequest.getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData value missing.");
        } else if (isNull(lookupDataRequest.getLookupType())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData type missing.");
        }
        LookupData lookupData = new LookupData();
        lookupData.setLookupValue(lookupDataRequest.getLookupValue());
        lookupData.setLookupType(lookupDataRequest.getLookupType());
        if (!isNull(lookupDataRequest.getDescription())) {
            lookupData.setDescription(lookupDataRequest.getDescription());
        }
        if (!isNull(lookupDataRequest.getParentLookupId())) {
            Optional<LookupData> parentLookupData = this.lookupDataRepository
                .findById(lookupDataRequest.getParentLookupId());
            if (parentLookupData.isPresent()) {
                lookupData.setParentLookup(parentLookupData.get());
            }
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            lookupDataRequest.getAccessUserDetail().getUsername(), Status.Active);
        if (appUser.isPresent()) {
            lookupData.setAppUser(appUser.get());
        }
        this.lookupDataRepository.save(lookupData);
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "LookupData save with %d.",lookupData.getLookupId()));
    }

    @Override
    public AppResponse updateLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        if (isNull(lookupDataRequest.getLookupId())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData id missing.");
        } else if (isNull(lookupDataRequest.getLookupValue())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData value missing.");
        } else if (isNull(lookupDataRequest.getLookupType())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData type missing.");
        }
        Optional<LookupData> lookupData = this.lookupDataRepository.findById(lookupDataRequest.getLookupId());
        if (lookupData.isPresent()) {
            lookupData.get().setLookupValue(lookupDataRequest.getLookupValue());
            lookupData.get().setLookupType(lookupDataRequest.getLookupType());
            if (!isNull(lookupDataRequest.getDescription())) {
                lookupData.get().setDescription(lookupDataRequest.getDescription());
            }
            if (!isNull(lookupDataRequest.getParentLookupId())) {
                Optional<LookupData> parentLookupData = this.lookupDataRepository
                    .findById(lookupDataRequest.getParentLookupId());
                if (parentLookupData.isPresent()) {
                    lookupData.get().setParentLookup(parentLookupData.get());
                }
            }
            this.lookupDataRepository.save(lookupData.get());
            return new AppResponse(ProcessUtil.SUCCESS, String.format(
                "LookupData update with %d.", lookupDataRequest.getLookupId()));
        }
        return new AppResponse(ProcessUtil.ERROR, String.format(
            "LookupData not found with %d.", lookupDataRequest.getLookupId()));
    }

    @Override
    public AppResponse fetchSubLookupByParentId(Long parentLookUpId) throws Exception {
        if (isNull(parentLookUpId)) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData id missing.");
        }
        Map<String, Object> appSettingDetail = new HashMap<>();
        List<LookupDataResponse> lookupDataResponses = new ArrayList<>();
        Optional<LookupData> parentLookup = this.lookupDataRepository.findById(parentLookUpId);
        if (parentLookup.isPresent()) {
            LookupDataResponse parentLookupDataResponse = new LookupDataResponse();
            this.fillLookupDateDto(parentLookup.get(), parentLookupDataResponse);
            appSettingDetail.put(PARENT_LOOKUP_DATA, parentLookupDataResponse);
            if (!isNull(parentLookup.get().getLookupChildren())) {
                for (LookupData lookup: parentLookup.get().getLookupChildren()) {
                    LookupDataResponse childLookupDataResponse = new LookupDataResponse();
                    this.fillLookupDateDto(lookup, childLookupDataResponse);
                    lookupDataResponses.add(childLookupDataResponse);
                }
            }
            appSettingDetail.put(LOOKUP_DATA, lookupDataResponses);
            return new AppResponse(ProcessUtil.SUCCESS,
                "Data fetch successfully.", appSettingDetail);
        }
        return new AppResponse(ProcessUtil.ERROR,
            String.format("LookupData not found with %d.", parentLookUpId));
    }

    @Override
    public AppResponse fetchAllLookup() throws Exception {
        return new AppResponse(ProcessUtil.SUCCESS,
        "Data fetch successfully.", this.getLookupCacheMap());
    }

    @Override
    public AppResponse deleteLookupData(LookupDataRequest lookupDataRequest) throws Exception {
        if (isNull(lookupDataRequest.getLookupId())) {
            return new AppResponse(ProcessUtil.ERROR, "LookupData id missing.");
        }
        this.lookupDataRepository.deleteById(lookupDataRequest.getLookupId());
        return new AppResponse(ProcessUtil.SUCCESS, String.format("LookupData delete with %d.",
            lookupDataRequest.getLookupId()));
    }

    private void fillLookupDateDto(LookupData lookupData, LookupDataResponse lookupDataResponse) {
        lookupDataResponse.setLookupId(lookupData.getLookupId());
        lookupDataResponse.setLookupValue(lookupData.getLookupValue());
        lookupDataResponse.setLookupType(lookupData.getLookupType());
        lookupDataResponse.setDescription(lookupData.getDescription());
        lookupDataResponse.setDateCreated(lookupData.getDateCreated());
    }

    private LookupDataResponse getLookupDataDetail(LookupData lookupData) {
        LookupDataResponse parentLookupData = new LookupDataResponse();
        parentLookupData.setLookupId(lookupData.getLookupId());
        parentLookupData.setLookupType(lookupData.getLookupType());
        parentLookupData.setLookupValue(lookupData.getLookupValue());
        parentLookupData.setDescription(lookupData.getDescription());
        parentLookupData.setDateCreated(lookupData.getDateCreated());
        if (lookupData.getLookupChildren().size() > 0) {
            parentLookupData.setLookupChildren(lookupData.getLookupChildren()
                .stream().map(childLookup -> {
                    LookupDataResponse childLookupData =new LookupDataResponse();
                    childLookupData.setLookupId(childLookup.getLookupId());
                    childLookupData.setLookupType(childLookup.getLookupType());
                    childLookupData.setLookupValue(childLookup.getLookupValue());
                    childLookupData.setDescription(childLookup.getDescription());
                    childLookupData.setDateCreated(childLookup.getDateCreated());
                    return childLookupData;
                }).collect(Collectors.toSet()));
        }
        return parentLookupData;
    }

    public LookupDataResponse getParentLookupById(String lookupType) {
        return this.lookupCacheMap.get(lookupType);
    }

    public LookupDataResponse getChildLookupById(String parentLookupType, String childLookupType) {
        return this.getParentLookupById(parentLookupType).getLookupChildren().stream()
            .filter(childLookup -> childLookupType.equals(childLookup.getLookupType()))
            .findAny().orElse(null);
    }

    public Map<String, LookupDataResponse> getLookupCacheMap() {
        return lookupCacheMap;
    }

    public void setLookupCacheMap(Map<String, LookupDataResponse> lookupCacheMap) {
        this.lookupCacheMap = lookupCacheMap;
    }

}
