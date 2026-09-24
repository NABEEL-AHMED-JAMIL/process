package process.util;

import org.junit.jupiter.api.Test;
import process.identity.IdentityPort;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-107: names on a list are for reading, never for deciding. With Identity out of reach the list is
 * still served -- the created-by and updated-by names are simply absent -- rather than failing whole.
 */
class UserNameResolverIdentityDownTest {

    private final IdentityPort identity = mock(IdentityPort.class);
    private final UserNameResolver resolver = new UserNameResolver(this.identity);

    @Test
    void aListStillComesBackWithoutItsNames() {
        when(this.identity.people(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));

        assertThat(this.resolver.namesFor(Arrays.asList(7L, 8L))).isEmpty();
    }

    @Test
    void aSingleNameIsAbsent() {
        when(this.identity.person(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));

        assertThat(this.resolver.nameFor(7L)).isNull();
    }
}
