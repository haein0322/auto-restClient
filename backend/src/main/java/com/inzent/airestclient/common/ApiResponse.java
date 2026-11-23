package com.inzent.airestclient.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 모든 API가 공통으로 사용하는 응답 래퍼 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

  public record ErrorBody(String code, String message) {}

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(false, null, new ErrorBody(code, message));
  }
}
