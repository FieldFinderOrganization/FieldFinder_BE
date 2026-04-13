package com.example.FieldFinder.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. Xử lý lỗi RuntimeException chung (Ví dụ: throw new RuntimeException("Sản phẩm không tồn tại"))
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        ex.printStackTrace();
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    // 2. Xử lý lỗi thiếu quyền (Lỗi 403 Forbidden do Spring Security văng ra)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập tài nguyên này!", request.getRequestURI());
    }

    // 3. Xử lý lỗi Validate (Xảy ra khi dùng @Valid ở Controller mà dữ liệu không hợp lệ)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Error");
        response.put("path", request.getRequestURI());

        // Lấy tất cả các lỗi và gom thành 1 chuỗi hoặc map
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        response.put("message", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 4. Xử lý lỗi sai tham số truyền vào (Ví dụ parse string thành UUID bị lỗi)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ: " + ex.getMessage(), request.getRequestURI());
    }

    // 5. Xử lý lỗi 404 Not Found (Khi gọi sai đường dẫn API)
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Object> handleNotFoundException(NoHandlerFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Không tìm thấy API (Đường dẫn không tồn tại): " + ex.getRequestURL(), request.getRequestURI());
    }

    // 6. Xử lý lỗi Database (Ví dụ: Trùng email, vi phạm khóa ngoại...)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolationException(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = "Lỗi toàn vẹn dữ liệu (Có thể do trùng lặp dữ liệu hoặc vi phạm ràng buộc).";
        return buildErrorResponse(HttpStatus.CONFLICT, message, request.getRequestURI());
    }

    // 7. Xử lý lỗi sai cú pháp JSON (Frontend gửi JSON bị thiếu ngoặc, sai format)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Định dạng JSON không hợp lệ hoặc không thể đọc được dữ liệu gửi lên.", request.getRequestURI());
    }

    // 8. Xử lý lỗi sai kiểu dữ liệu của biến trên URL (VD: Yêu cầu số nhưng truyền chữ)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = String.format("Tham số '%s' phải là kiểu '%s'", ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "không xác định");
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    // 9. Xử lý lỗi sai Method (Ví dụ API là POST nhưng lại dùng GET)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String message = String.format("Method '%s' không được hỗ trợ cho đường dẫn này. Vui lòng sử dụng '%s'", ex.getMethod(), ex.getSupportedHttpMethods());
        return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, message, request.getRequestURI());
    }

    // 10. Xử lý thiếu Request Param (Ví dụ API yêu cầu ?categories= nhưng không truyền)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParams(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = String.format("Thiếu tham số bắt buộc trên URL: '%s'", ex.getParameterName());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    // 11. Bắt tất cả các lỗi còn lại (Tránh lộ mã lỗi hệ thống rác ra ngoài)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGlobalException(Exception ex, HttpServletRequest request) {
        ex.printStackTrace(); // In ra log console để dev fix
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau!", request.getRequestURI());
    }

    // --- Hàm hỗ trợ build cấu trúc JSON trả về chung ---
    private ResponseEntity<Object> buildErrorResponse(HttpStatus status, String message, String path) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("message", message);
        response.put("path", path); // Thêm đường dẫn API bị lỗi để dễ trace bug

        return ResponseEntity.status(status).body(response);
    }
}