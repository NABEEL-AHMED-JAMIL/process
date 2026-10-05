package process.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import process.forms.FormFields;
import process.forms.FormService;
import process.forms.FormSubmissionService;
import process.forms.PublicForms;
import process.model.dto.ResponseDto;

import javax.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Scale review P0 #4: a form upload larger than any field takes is refused by its size before a byte of it is read into
 * memory -- on the members' endpoint and on a share link's, which anyone holding a link can call.
 */
class FormUploadSizeTest {

    private static MultipartFile huge() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(FormFields.LARGEST_UPLOAD_BYTES + 1);
        when(file.getOriginalFilename()).thenReturn("huge.pdf");
        return file;
    }

    @Test
    void aMembersUploadOverTheLargestFieldIsRefusedUnread() throws Exception {
        FormSubmissionService submissions = mock(FormSubmissionService.class);
        MultipartFile file = huge();

        ResponseEntity<?> answer = new FormRestApi(mock(FormService.class), submissions).upload(1000L, "doc", file);

        assertThat(((ResponseDto) answer.getBody()).getStatus()).isEqualTo("ERROR");
        assertThat(((ResponseDto) answer.getBody()).getMessage()).isEqualTo("A form takes files of at most 25 MB.");
        verify(file, never()).getBytes();
        verifyNoInteractions(submissions);
    }

    @Test
    void aShareLinkUploadOverTheLargestFieldIsRefusedUnread() throws Exception {
        PublicForms forms = mock(PublicForms.class);
        MultipartFile file = huge();

        ResponseEntity<?> answer = new PublicFormRestApi(forms).upload("token", "ticket", "doc", file, mock(HttpServletRequest.class));

        assertThat(((ResponseDto) answer.getBody()).getStatus()).isEqualTo("ERROR");
        verify(file, never()).getBytes();
        verify(forms, never()).upload(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
