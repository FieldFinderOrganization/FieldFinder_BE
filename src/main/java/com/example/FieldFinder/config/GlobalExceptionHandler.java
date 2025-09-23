//package com.example.FieldFinder.config;
//
//import jakarta.servlet.http.HttpServletRequest;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.ControllerAdvice;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.ResponseBody;
//import org.springframework.web.server.ResponseStatusException;
//
//import java.time.LocalDateTime;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
//@ControllerAdvice
//public class GlobalExceptionHandler {
//
//    @ExceptionHandler(ResponseStatusException.class)
//    @ResponseBody
//    public ResponseEntity<Object> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
//        Map<String, Object> body = new LinkedHashMap<>();
//        body.put("timestamp", LocalDateTime.now());
//        body.put("status", ex.getStatusCode().value());
//        body.put("error", ex.getStatusCode());
//        body.put("message", ex.getReason());
//        body.put("path", request.getRequestURI()); // ✅ inject HttpServletRequest
//
//        return new ResponseEntity<>(body, ex.getStatusCode());
//    }
//}
