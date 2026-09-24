package process.util;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import process.directory.UserDirectory;
import process.identity.IdentityPort;
import process.model.pojo.SourceJob;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-153: UserNameResolver asks user_directory first and Identity only for what the directory does not
 * hold -- one query per list and at most one call, however long the list, and the answers written back.
 * A projection that is only nearly complete still renders every name and costs exactly one call.
 */
class UserNameResolverDirectoryTest {

    private final IdentityPort identity = mock(IdentityPort.class);
    private final UserDirectory directory = mock(UserDirectory.class);
    private final UserNameResolver resolver = new UserNameResolver(this.identity, this.directory);

    private static UserDirectory.Entry held(long id, String fullName) {
        return new UserDirectory.Entry(id, 2901L, "u" + id + "@a.example", fullName, "Active", Instant.parse("2026-09-24T10:00:00Z"));
    }

    private static IdentityPort.Person person(long id, String fullName) {
        return new IdentityPort.Person(id, 2901L, "u" + id + "@a.example", fullName, "TENANT_USER", "Active");
    }

    private void directoryHolds(UserDirectory.Entry... entries) {
        Map<Long, UserDirectory.Entry> held = new HashMap<>();
        for (UserDirectory.Entry entry : entries) held.put(entry.getAppUserId(), entry);
        when(this.directory.find(any())).thenAnswer(call -> {
            Map<Long, UserDirectory.Entry> answer = new HashMap<>();
            for (Long id : call.<Collection<Long>>getArgument(0)) if (held.containsKey(id)) answer.put(id, held.get(id));
            return answer;
        });
    }

    @Test
    void aCompleteProjectionAnswersWithoutAskingIdentity() {
        directoryHolds(held(7, "Ada"), held(8, "Grace"));

        assertThat(this.resolver.namesFor(Arrays.asList(7L, 8L))).containsEntry(7L, "Ada").containsEntry(8L, "Grace");
        verify(this.identity, never()).people(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNearlyCompleteProjectionRendersEveryNameWithExactlyOneCallForTheMissingOnly() {
        directoryHolds(held(7, "Ada"));
        Map<Long, IdentityPort.Person> answer = new HashMap<>();
        answer.put(8L, person(8, "Grace"));
        answer.put(9L, person(9, "Linus"));
        when(this.identity.people(any())).thenReturn(answer);
        Instant before = Instant.now();

        Map<Long, String> names = this.resolver.namesFor(Arrays.asList(7L, 8L, 9L, 10L));

        assertThat(names).containsOnlyKeys(7L, 8L, 9L).containsEntry(8L, "Grace");
        ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(this.identity, times(1)).people(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder(8L, 9L, 10L);
        ArgumentCaptor<Collection<UserDirectory.Entry>> written = ArgumentCaptor.forClass(Collection.class);
        verify(this.directory, times(1)).writeBack(written.capture());
        assertThat(written.getValue()).extracting(UserDirectory.Entry::getAppUserId).containsExactlyInAnyOrder(8L, 9L);
        verify(this.directory, never()).apply(any());
        assertThat(written.getValue()).allSatisfy(entry ->
            assertThat(entry.getUpdatedAt()).as("stamped when the question was asked").isBetween(before, Instant.now()));
    }

    @Test
    void aWholeListCostsOneDirectoryQueryAndAtMostOneCall() {
        directoryHolds(held(1, "One"), held(2, "Two"));
        when(this.identity.people(any())).thenReturn(Collections.singletonMap(3L, person(3, "Three")));
        List<SourceJob> jobs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            SourceJob job = new SourceJob();
            job.setCreatedBy((long) (i % 3) + 1);
            job.setUpdatedBy((long) ((i + 1) % 3) + 1);
            jobs.add(job);
        }

        this.resolver.attachNames(jobs);

        verify(this.directory, times(1)).find(any());
        verify(this.identity, times(1)).people(any());
        assertThat(jobs).allSatisfy(job -> assertThat(job.getCreatedByName()).isNotNull());
    }

    @Test
    void aDirectoryThatCannotBeReadFallsBackToIdentity() {
        when(this.directory.find(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        when(this.identity.people(any())).thenReturn(Collections.singletonMap(7L, person(7, "Ada")));

        assertThat(this.resolver.namesFor(Collections.singletonList(7L))).containsEntry(7L, "Ada");
    }

    @Test
    void identityOutOfReachStillServesWhatTheDirectoryHolds() {
        directoryHolds(held(7, "Ada"));
        when(this.identity.people(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));

        assertThat(this.resolver.namesFor(Arrays.asList(7L, 8L))).containsOnlyKeys(7L);
    }

    @Test
    void aSingleNameGoesTheSameWay() {
        directoryHolds(held(7, "Ada"));
        assertThat(this.resolver.nameFor(7L)).isEqualTo("Ada");
        verify(this.identity, never()).people(any());
        verify(this.identity, never()).person(any());
    }
}
