package process.identity;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.security.PageGate;
import process.security.TokenRevocations;
import process.util.JwtUtil;

import static org.mockito.Mockito.mock;

/**
 * IdentityPort for tests of the code on the far side of it (MIG-93): the real LocalIdentity over the
 * repositories a test already mocks and stubs, so a test that used to hand a service AppUserRepository
 * hands it this instead and keeps its stubs.
 */
public final class TestIdentity {

    private TestIdentity() {
    }

    public static IdentityPort over(AppUserRepository users, TenantRepository tenants) {
        return over(users, tenants, null);
    }

    public static IdentityPort over(AppUserRepository users, TenantRepository tenants, PageGate gate) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (gate != null) {
            beans.registerSingleton("pageGate", gate);
        }
        return new LocalIdentity(mock(JwtUtil.class), mock(TokenRevocations.class),
            users != null ? users : mock(AppUserRepository.class), tenants != null ? tenants : mock(TenantRepository.class),
            beans.getBeanProvider(PageGate.class));
    }

    /** Authentication as process does it: these tokens, these revocations. */
    public static IdentityPort authenticating(JwtUtil jwt, TokenRevocations revocations) {
        return new LocalIdentity(jwt, revocations, mock(AppUserRepository.class), mock(TenantRepository.class),
            new DefaultListableBeanFactory().getBeanProvider(PageGate.class));
    }

    /** Only the page gate. */
    public static IdentityPort gate(PageGate gate) {
        return over(null, null, gate);
    }
}
