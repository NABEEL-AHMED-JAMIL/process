package process.model.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-47: invoice lines are read and deleted by invoice id AND tenant, never by invoice id alone --
 * the tenant filter only runs where a session enabled it, and the invoice id is whatever the caller
 * sent. A finder added later without the tenant fails here.
 */
class InvoiceLineRepositoryGuardTest {

    @Test
    void everyFinderAndDeleteNamesTheTenant() {
        List<String> unguarded = Arrays.stream(InvoiceLineRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.startsWith("find") || name.startsWith("delete") || name.startsWith("count") || name.startsWith("exists"))
            .filter(name -> !name.contains("TenantId"))
            .collect(Collectors.toList());
        assertThat(unguarded).isEmpty();
        assertThat(InvoiceLineRepository.class.getDeclaredMethods()).isNotEmpty();
    }
}
