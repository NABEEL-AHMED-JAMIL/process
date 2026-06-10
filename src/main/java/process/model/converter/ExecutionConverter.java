package process.model.converter;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import process.model.enums.Execution;
import process.util.EnumUtils;

@Converter(autoApply = true)
public class ExecutionConverter implements AttributeConverter<Execution, String> {

    @Override
    public String convertToDatabaseColumn(Execution attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public Execution convertToEntityAttribute(String dbData) {
        return EnumUtils.parseEnum(Execution.class, dbData);
    }
}

