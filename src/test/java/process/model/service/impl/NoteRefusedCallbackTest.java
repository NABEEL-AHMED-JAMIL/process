package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.BulkAction;
import process.model.enums.JobStatus;
import process.notifications.JobMail;
import process.notifications.TestNotifications;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MIG-63: noting a refused report writes the note and nothing else -- no status, no audit line, no push.
 * The note is the application clock's, not the database's.
 */
@ExtendWith(MockitoExtension.class)
class NoteRefusedCallbackTest {

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(null, null, null, null));
    }

    @Test
    void theRefusedStatusIsNotedOnTheRunAndNothingElseMoves() {
        LocalDateTime before = LocalDateTime.now();
        this.service.noteRefusedCallback(5705L, JobStatus.Completed);
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(this.transactionService).noteRefusedCallback(eq(5705L), at.capture(), eq("Completed"));
        assertThat(at.getValue()).isBetween(before, after);
        verifyNoInteractions(this.bulkAction, this.jobMail);
    }

    @Test
    void aRefusedLogLineIsNotedAsOne() {
        this.service.noteRefusedCallback(5705L, null);

        verify(this.transactionService).noteRefusedCallback(eq(5705L), any(LocalDateTime.class), eq("log"));
    }
}
