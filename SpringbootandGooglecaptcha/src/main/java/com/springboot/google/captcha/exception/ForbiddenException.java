package com.springboot.google.captcha.exception;

// customer class to throw the forbidden exception if the captcha response
// is not valid.
public class ForbiddenException extends Exception {

	private static final long serialVersionUID = 1L;

	public ForbiddenException(final String message) {
        super(message);
    }
}
