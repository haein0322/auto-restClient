package com.inzent.airestclient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 통신 테스트(5단계) DTO */
public final class TestDtos {

  private TestDtos() {}

  /**
   * 실행 옵션. apiKey는 저장하지 않으며 요청에만 사용한다.
   *
   * @param confirmExternal 허용 호스트(localhost 등) 밖의 서버를 호출한다는 명시적 확인
   * @param allowDestructive 삭제 등 DESTRUCTIVE 위험도 호출 실행 허용
   * @param timeoutSeconds 호출당 읽기 타임아웃(1~60초, 생략 시 15초)
   */
  public record TestOptions(String baseUrl, String apiKey, boolean allowDestructive, boolean confirmExternal,
      Integer timeoutSeconds) {}

  /** 화면 입력란의 기본값 */
  public record TestDefaults(String baseUrl, String apiKey, boolean builtInMock) {}

  /** @param outcome PASS | FAIL | SKIPPED */
  public record TestCaseView(
      String operationId,
      String method,
      String path,
      String outcome,
      Integer statusCode,
      Long elapsedMs,
      Boolean schemaValid,
      JsonNode request,
      String responseBody,
      String failureReason) {}

  public record TestRunView(
      Long runId,
      Long specId,
      String baseUrl,
      LocalDateTime createdAt,
      int total,
      int passed,
      int failed,
      int skipped,
      List<TestCaseView> cases,
      DiagnosisView diagnosis) {}

  public record TestRunSummary(Long runId, String baseUrl, LocalDateTime createdAt, int total, int passed, int failed,
      int skipped) {}

  // ---------------- AI 원인 분석 ----------------

  public record CaseDiagnosis(
      @JsonPropertyDescription("입력으로 받은 실패 케이스의 operationId 그대로") String operationId,
      @JsonPropertyDescription("실패 원인을 한국어 1~2문장으로 설명") String likelyCause,
      @JsonPropertyDescription("해결 방법을 한국어 1~2문장으로 제안. 샘플을 고쳐야 하면 어떤 필드를 어떻게 바꿀지 구체적으로") String suggestion) {}

  /** AI가 반환하는 원인 분석(서버 검증 전) */
  public record AiDiagnosisOutput(
      @JsonPropertyDescription("전체 실패 양상을 한국어 2~3문장으로 요약") String summary,
      @JsonPropertyDescription("실패한 케이스마다 하나씩") List<CaseDiagnosis> cases) {}

  public record DiagnosisView(String provider, LocalDateTime diagnosedAt, String summary, List<CaseDiagnosis> cases) {}
}
