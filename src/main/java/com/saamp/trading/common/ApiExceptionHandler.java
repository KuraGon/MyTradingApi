package com.saamp.trading.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TradingException.class)
    org.springframework.http.ResponseEntity<?> handleTradingException(TradingException ex) {
        if (java.util.Set.of("OFFICIAL_BALANCE_UNAVAILABLE", "OFFICIAL_CURRENCY_UNSUPPORTED",
                "PLATFORM_TEMPORARILY_UNAVAILABLE").contains(ex.getCode())) {
            // Ne pas recopier le message ni les propriétés potentiellement techniques de l'exception.
            return org.springframework.http.ResponseEntity.status(503)
                    .header("Retry-After", "30").header("Cache-Control", "no-store")
                    .body(java.util.Map.of("code", "PLATFORM_TEMPORARILY_UNAVAILABLE",
                            "message", "La plateforme est momentanément indisponible pour des raisons techniques."));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setType(URI.create("urn:saamp:trading:error:" + ex.getCode().toLowerCase()));
        problem.setProperty("code", ex.getCode());
        ex.getProperties().forEach(problem::setProperty);
        return org.springframework.http.ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(400);
        problem.setTitle("VALIDATION_ERROR");
        problem.setDetail(ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).findFirst().orElse("Requête invalide"));
        problem.setProperty("code", "VALIDATION_ERROR");
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(409);
        problem.setTitle("DATA_INTEGRITY_VIOLATION");
        problem.setDetail("L'opération violerait un invariant financier en base");
        problem.setProperty("code", "DATA_INTEGRITY_VIOLATION");
        return problem;
    }
}
