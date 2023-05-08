package process.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import org.springframework.transaction.annotation.Transactional;
import process.model.repository.LookupDataRepository;
import process.util.lookuputil.Status;

/**
 * @author Nabeel Ahmed
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;

    /**
     * loadUserByUsername method provide the auth user detail
     * @param username
     * @return UserDetails
     * */
    @Override
    @Transactional
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByUsernameAndStatus(username, Status.ACTIVE.getLookupValue())
            .orElseThrow(() -> new UsernameNotFoundException("User Not Found with username: " + username));
        return UserDetailsImpl.build(appUser);
    }
}
