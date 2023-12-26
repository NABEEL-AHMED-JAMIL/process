package process.mongo.documents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.mongodb.core.mapping.Document;
import javax.persistence.Id;

/**
 * @author Nabeel Ahmed
 * File Detail
 * */
@Document(collection = "fileAnalytics")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileAnalytics {

    @Id
    private String id;
}
