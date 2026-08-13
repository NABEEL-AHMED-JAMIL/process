package process.config;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DocumentConverterConfig {

    @Bean(name = "localDocumentConverter")
    public DocumentConverter localDocumentConverter(OfficeManager officeManager, DocumentFormatRegistry documentFormatRegistry) {
        Map<String, Object> filterData = new HashMap<>();

        filterData.put("ExportBookmarks", false);
        filterData.put("ExportNotes", false);

        Map<String, Object> storeProperties = new HashMap<>();
        storeProperties.put("FilterData", filterData);

        return LocalConverter.builder()
            .officeManager(officeManager)
            .formatRegistry(documentFormatRegistry)
            .storeProperties(storeProperties)
            .build();
    }

}
