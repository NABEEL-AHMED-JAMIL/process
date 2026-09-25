package process.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On every Identity bean inside process (IdentityPortBoundaryTest.IDENTITY): it exists only while Identity
 * runs here, identity.mode=local, the default. With identity.mode=remote (MIG-107) none of them is made --
 * no key minted, nothing read from the six tables -- and {@link HttpIdentity} answers the port instead.
 * IdentityInProcessTest holds every bean to it. Identity's endpoints (/auth.json, /appUser.json, /tenant.json,
 * /tenantRequest.json, /pageAccess.json) and the services behind them left process in MIG-108; what is left
 * in local mode is the read side LocalIdentity answers the port from, for the tests and harnesses that run
 * process on its own.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(name = "identity.mode", havingValue = "local", matchIfMissing = true)
public @interface IdentityInProcess {
}
