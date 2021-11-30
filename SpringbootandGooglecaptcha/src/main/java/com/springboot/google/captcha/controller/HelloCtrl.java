package com.springboot.google.captcha.controller;

import com.springboot.google.captcha.exception.ForbiddenException;
import com.springboot.google.captcha.model.HelloDto;
import com.springboot.google.captcha.model.HelloResponseDto;
import com.springboot.google.captcha.service.ValidateCaptcha;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// Lombok annotation for logger
@Slf4j
// Spring annotations
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HelloCtrl {
 
	// injected to validate the captcha response coming in the request.
    @Autowired
    ValidateCaptcha service;

    // URL - http://localhost:9001/api/welcome
    @PostMapping("/welcome")
    @ResponseStatus(code = HttpStatus.OK)
    public HelloResponseDto welcome(@RequestBody final HelloDto dto)
            throws ForbiddenException {
        final boolean isValidCaptcha = service.validateCaptcha(dto.getCaptchaResponse());
        if (!isValidCaptcha) {
            log.info("Throwing forbidden exception as the captcha is invalid.");
            throw new ForbiddenException("INVALID_CAPTCHA");
        }

        return new HelloResponseDto("Greetings " + dto.getName());
    }
}
