package com.inzent.airestclient.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
  INVALID_SPEC(HttpStatus.UNPROCESSABLE_ENTITY, "Swagger 명세를 파싱할 수 없습니다."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
  ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 결과가 없습니다. 먼저 AI 분석을 실행하세요."),
  CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "생성된 클라이언트가 없습니다. 먼저 코드를 생성하세요."),
  TEST_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "테스트 실행 결과를 찾을 수 없습니다."),
  SAMPLES_NOT_FOUND(HttpStatus.NOT_FOUND, "생성된 샘플이 없습니다. 먼저 샘플을 생성하세요."),
  STEP_NOT_READY(HttpStatus.CONFLICT, "이전 단계가 완료되지 않았습니다."),
  GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "코드 생성에 실패했습니다."),
  AI_FAILED(HttpStatus.BAD_GATEWAY, "AI 호출에 실패했습니다."),
  TEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "통신 테스트 실행에 실패했습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
