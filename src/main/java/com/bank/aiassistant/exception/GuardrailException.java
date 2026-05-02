package com.bank.aiassistant.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GuardrailException extends RuntimeException {
    public GuardrailException(String message) { super(message); }
}
