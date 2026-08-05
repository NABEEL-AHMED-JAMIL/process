package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextCleanRequestDto {

    private String text;

    public TextCleanRequestDto() {}

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

}
