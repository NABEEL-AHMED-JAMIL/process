package com.springboot.google.captcha.responsemodel;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

// Lombok annotations
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CaptchaResponse {

	boolean success;
	LocalDateTime challenge_ts;
	String hostname;
	@JsonProperty("error-codes")
	List<String> errorCodes;
}
