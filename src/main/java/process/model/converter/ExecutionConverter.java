package process.model.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import process.model.enums.Execution;
import process.util.EnumUtils;

/**
 * @author Nabeel Ahmed
 * */
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
