package process.e2e;

import org.jodconverter.core.document.DocumentFormatRegistry;
import org.jodconverter.core.office.OfficeManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("e2e")
class ContextProbeIT {

    @MockBean private OfficeManager officeManager;
    @MockBean private DocumentFormatRegistry documentFormatRegistry;

    @Test
    void contextLoads() { }
}
