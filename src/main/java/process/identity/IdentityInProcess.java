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
 * no sign-in, no key minted, nothing written to the six tables, no /auth.json or /appUser.json answered --
 * and {@link HttpIdentity} answers the port instead. IdentityInProcessTest holds every bean to it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(name = "identity.mode", havingValue = "local", matchIfMissing = true)
public @interface IdentityInProcess {
}
