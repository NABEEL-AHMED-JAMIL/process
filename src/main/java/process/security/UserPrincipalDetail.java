package process.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.util.ProcessUtil;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserPrincipalDetail extends User implements OAuth2User, OidcUser {

    private AppUser appUser;
    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;
    private Map<String, Object> attributes;
    private Collection<? extends GrantedAuthority> authorities;

    public UserPrincipalDetail(final String emailAddress, final String password, final boolean enabled,
       final boolean accountNonExpired, final boolean credentialsNonExpired, final boolean accountNonLocked,
       final Collection<? extends GrantedAuthority> authorities, final AppUser appUser) {
        this(emailAddress, password, enabled, accountNonExpired, credentialsNonExpired,
            accountNonLocked, authorities, appUser, null, null);
    }

    public UserPrincipalDetail(final String emailAddress, final String password, final boolean enabled,
       final boolean accountNonExpired, final boolean credentialsNonExpired, final boolean accountNonLocked,
       final Collection<? extends GrantedAuthority> authorities, final AppUser appUser, OidcIdToken idToken, OidcUserInfo userInfo) {
        super(emailAddress, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
        this.appUser = appUser;
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    public static UserPrincipalDetail create(AppUser appUser, Map<String, Object> attributes,
        OidcIdToken idToken, OidcUserInfo userInfo) {
        UserPrincipalDetail userPrincipalDetail = new UserPrincipalDetail(appUser.getEmail(), appUser.getPassword(),
        appUser.getStatus().equals(Status.Active) ? true : false, true, true, true,
        ProcessUtil.buildSimpleGrantedAuthorities(Collections.singleton(appUser.getRoles().toString())), appUser, idToken, userInfo);
        userPrincipalDetail.setAttributes(attributes);
        return userPrincipalDetail;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    @Override
    public String getName() {
        return this.appUser.getDisplayName();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return this.attributes;
    }

    @Override
    public Map<String, Object> getClaims() {
        return this.attributes;
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return this.userInfo;
    }

    @Override
    public OidcIdToken getIdToken() {
        return this.idToken;
    }

    public void setAuthorities(Collection<? extends GrantedAuthority> authorities) {
        this.authorities = authorities;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}