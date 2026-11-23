package com.inzent.airestclient.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 예외를 공통 응답 형식(ApiResponse)으로 변환 */
@RestControllerAdvice(basePackages = "com.inzent.airestclient")
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
    ErrorCode code = e.getErrorCode();
    if (code.status().is5xxServerError()) {
      log.error("비즈니스 예외(서버 오류): {}", e.getMessage(), e);
    } else {
      log.warn("비즈니스 예외: {} - {}", code, e.getMessage());
    }
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code.name(), e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
  public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
    ErrorCode code = ErrorCode.INVALID_REQUEST;
    return ResponseEntity.status(code.status()).body(ApiResponse.fail(code.name(), e.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
    ErrorCode code = ErrorCode.INVALID_REQUEST;
    return ResponseEntity.status(code.status())
        .body(ApiResponse.fail(code.name(), "업로드 파일 크기가 제한을 초과했습니다."));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
    log.error("예상하지 못한 오류", e);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return ResponseEntity.status(code.status())
        .body(ApiResponse.fail(code.name(), code.defaultMessage()));
  }
}
