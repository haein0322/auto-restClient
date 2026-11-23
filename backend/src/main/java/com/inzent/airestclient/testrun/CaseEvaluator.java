package com.inzent.airestclient.testrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

/**
 * 호출 결과를 PASS/FAIL로 판정하는 순수 로직(네트워크·리플렉션과 분리해 단위 테스트 가능하게 했다).
 * 성공 조건: 2xx, 명세에 선언된 상태 코드, 응답 본문이 명세의 응답 스키마를 만족.
 */
final class CaseEvaluator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_REASON = 500;
  private static final int MAX_SCHEMA_ERRORS = 5;

  /** @param outcome PASS | FAIL */
  record Evaluation(String outcome, Integer statusCode, Boolean schemaValid, String failureReason) {

    static Evaluation pass(int status, Boolean schemaValid) {
      return new Evaluation("PASS", status, schemaValid, null);
    }

    static Evaluation fail(Integer status, Boolean schemaValid, String reason) {
      return new Evaluation("FAIL", status, schemaValid, truncate(reason));
    }
  }

  private CaseEvaluator() {}

  /** 예외 없이 응답을 받은 경우(생성된 클라이언트는 4xx/5xx를 예외로 던지므로 보통 2xx) */
  static Evaluation evaluate(OpenApiSchemaValidator validator, String path, String method, int status, String body) {
    if (status < 200 || status >= 300) {
      return Evaluation.fail(status, null, "예상한 2xx가 아닌 HTTP " + status + " 응답입니다.");
    }
    if (!validator.declaresResponse(path, method, status)) {
      return Evaluation.fail(status, null, "명세에 선언되지 않은 상태 코드입니다: " + status);
    }
    var schema = validator.responseSchema(path, method, status);
    if (schema.isEmpty()) {
      return Evaluation.pass(status, null); // 본문 없는 응답(예: 204)
    }
    if (body == null || body.isBlank()) {
      return Evaluation.fail(status, false, "명세는 응답 본문을 정의했지만 본문이 비어 있습니다.");
    }
    JsonNode json;
    try {
      json = MAPPER.readTree(body);
    } catch (JsonProcessingException e) {
      return Evaluation.fail(status, false, "응답 본문이 올바른 JSON이 아닙니다.");
    }
    List<String> errors = validator.validate(schema.get(), json);
    if (!errors.isEmpty()) {
      return Evaluation.fail(status, false, "응답이 명세의 스키마와 다릅니다: "
          + String.join("; ", errors.stream().limit(MAX_SCHEMA_ERRORS).toList()));
    }
    return Evaluation.pass(status, true);
  }

  /** 서버가 4xx/5xx로 응답해 ApiException이 던져진 경우 */
  static Evaluation fromApiError(int status, String body) {
    String detail = "";
    if (body != null && !body.isBlank()) {
      try {
        JsonNode json = MAPPER.readTree(body);
        String code = json.path("code").asText("");
        String message = json.path("message").asText("");
        detail = code.isEmpty() && message.isEmpty() ? " — " + body : " — " + code + (message.isEmpty() ? "" : ": " + message);
      } catch (JsonProcessingException e) {
        detail = " — " + body;
      }
    }
    return Evaluation.fail(status, null, "서버가 HTTP " + status + " 오류로 응답했습니다" + detail);
  }

  /** 연결 실패, 타임아웃, 응답 변환 실패 등 HTTP 응답 이전/이후의 예외 */
  static Evaluation fromException(Throwable error, Integer capturedStatus) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException
          || t instanceof HttpConnectTimeoutException) {
        return Evaluation.fail(capturedStatus, null, "응답 시간이 초과되었습니다. 서버가 느리거나 타임아웃 설정이 짧습니다.");
      }
      if (t instanceof ConnectException || t instanceof java.nio.channels.ClosedChannelException) {
        return Evaluation.fail(capturedStatus, null, "서버에 연결할 수 없습니다. 주소와 포트, 서버 실행 여부를 확인하세요.");
      }
      if (t instanceof java.net.UnknownHostException) {
        return Evaluation.fail(capturedStatus, null, "호스트를 찾을 수 없습니다: " + t.getMessage());
      }
    }
    String message = error.getMessage() == null ? "" : ": " + error.getMessage();
    boolean conversion = error.getClass().getSimpleName().contains("RestClientException")
        || error.getClass().getSimpleName().contains("HttpMessageNotReadable")
        || (error.getCause() != null && error.getCause() instanceof JsonProcessingException);
    if (conversion) {
      return Evaluation.fail(capturedStatus, false, "응답을 클라이언트 모델로 변환하지 못했습니다(명세와 다른 응답 형식일 수 있음)" + message);
    }
    return Evaluation.fail(capturedStatus, null, error.getClass().getSimpleName() + message);
  }

  private static String truncate(String text) {
    return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) + "…" : text;
  }
}
