package com.springboot.google.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Lombok annotation for logger
@Slf4j
// Spring annotation
@SpringBootApplication
public class SpringbootandGooglecaptchaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringbootandGooglecaptchaApplication.class, args);
        log.info("Springboot and google captcha application started successfully.");
    }

}
