package com.inzent.airestclient.mock;

import org.springframework.http.HttpStatus;

/** Mock 서버에서 샘플 명세의 에러 응답(ErrorResponse)으로 변환되는 예외 */
public class MockApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public MockApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
