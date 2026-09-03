package io.semanticmap.platform.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Problems {
    private static final Logger log = LoggerFactory.getLogger(Problems.class);

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException e, HttpServletRequest request) {
        return detail(e.getStatusCode().value(), e.getReason() == null ? "Request rejected" : e.getReason(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ProblemDetail validation(Exception e, HttpServletRequest request) {
        var result = detail(
                400, e instanceof MethodArgumentNotValidException ? "Invalid request fields" : e.getMessage(), request);
        if (e instanceof MethodArgumentNotValidException v)
            result.setProperty(
                    "fieldErrors",
                    v.getBindingResult().getFieldErrors().stream()
                            .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                            .toList());
        return result;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e, HttpServletRequest request) {
        return detail(409, "Operation conflicts with existing data", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        boolean tooLarge = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause())
            if ("REQUEST_SIZE_LIMIT".equals(cause.getMessage())) tooLarge = true;
        return detail(
                tooLarge ? 413 : 400,
                tooLarge ? "Request exceeds 2 MiB" : "Invalid or excessive JSON payload",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        var result = detail(500, "The request could not be completed", request);
        log.error("Request failed correlationId={}", result.getProperties().get("correlationId"), e);
        return result;
    }

    private ProblemDetail detail(int status, String message, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", "HTTP_" + status);
        problem.setProperty(
                "correlationId",
                request.getAttribute("correlationId") == null
                        ? UUID.randomUUID().toString()
                        : request.getAttribute("correlationId"));
        return problem;
    }
}
