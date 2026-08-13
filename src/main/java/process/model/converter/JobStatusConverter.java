package process.model.converter;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import process.model.enums.JobStatus;
import process.util.EnumUtils;

@Converter(autoApply = true)
public class JobStatusConverter implements AttributeConverter<JobStatus, String> {

    @Override
    public String convertToDatabaseColumn(JobStatus attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public JobStatus convertToEntityAttribute(String dbData) {
        return EnumUtils.parseEnum(JobStatus.class, dbData);
    }
}
