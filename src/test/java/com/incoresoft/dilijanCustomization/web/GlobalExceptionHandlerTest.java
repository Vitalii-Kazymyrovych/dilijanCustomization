package com.incoresoft.dilijanCustomization.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgumentReturnsBadRequestBody() {
        var response = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("code")).isEqualTo("bad_request");
        assertThat(body.get("message")).isEqualTo("bad input");
        assertThat(body.get("timestamp")).isNotNull();
    }

    @Test
    void handleValidationReturnsValidationErrorCode() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getMessage()).thenReturn("validation failed");

        var response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("code")).isEqualTo("validation_error");
    }

    @Test
    void handleAnyReturnsInternalServerErrorWithoutLeakingMessage() {
        var response = handler.handleAny(new RuntimeException("secret failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("code")).isEqualTo("internal_error");
        assertThat(body.get("message")).isEqualTo("Unexpected error");
    }
}
