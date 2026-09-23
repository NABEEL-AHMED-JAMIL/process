package process.api;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.model.service.AppUserService;
import java.net.ConnectException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A picture whose object is gone is "no picture" -- 404, and the console draws initials -- not "the
 * server broke". The row still names an avatar key after the object went (a store that keeps nothing
 * across restarts, a bucket cleaned by hand), and every page that shows the user asked for it: one 500
 * with a stack trace per page load. Seen live while verifying MIG-65's trusted avatar read.
 */
class AvatarNotFoundTest {

    private final AppUserService users = mock(AppUserService.class);

    @Test
    void aPictureWhoseObjectIsGoneIsA404() throws Exception {
        ErrorResponse noSuchKey = new ErrorResponse("NoSuchKey", "gone", "etl-avatar", "1000/profile/avatar.jpg", "req", "host", "id");
        when(this.users.readAvatar(1000L)).thenThrow(new RuntimeException("Could not fetch S3 object content etl-avatar/1000/profile/avatar.jpg",
            new ErrorResponseException(noSuchKey, null, null)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppUserRestApi(this.users)).build();

        mvc.perform(get("/appUser.json/avatar").param("appUserId", "1000")).andExpect(status().isNotFound());
    }

    @Test
    void aStoreThatIsDownIsStillA500() throws Exception {
        when(this.users.readAvatar(1000L)).thenThrow(new RuntimeException("Could not fetch", new ConnectException("refused")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AppUserRestApi(this.users)).build();

        mvc.perform(get("/appUser.json/avatar").param("appUserId", "1000")).andExpect(status().isInternalServerError());
    }
}
