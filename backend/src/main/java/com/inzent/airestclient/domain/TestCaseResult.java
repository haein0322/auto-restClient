package com.inzent.airestclient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 통신 테스트의 엔드포인트별 결과(실행 순서대로 id가 증가한다). */
@Entity
@Table(name = "test_case_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestCaseResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "operation_id", nullable = false)
  private String operationId;

  @Column(nullable = false)
  private String method;

  @Column(nullable = false)
  private String path;

  @Column(name = "status_code")
  private Integer statusCode;

  @Column(name = "elapsed_ms")
  private Long elapsedMs;

  @Column(name = "schema_valid")
  private Boolean schemaValid;

  @Column(nullable = false)
  private boolean passed;

  /** PASS | FAIL | SKIPPED */
  @Column(nullable = false)
  private String outcome;

  @Lob
  @Column(name = "request_json")
  private String requestJson;

  @Lob
  @Column(name = "response_json")
  private String responseBody;

  @Lob
  @Column(name = "failure_reason")
  private String failureReason;

  public TestCaseResult(Long runId, String operationId, String method, String path, Integer statusCode,
      Long elapsedMs, Boolean schemaValid, String outcome, String requestJson, String responseBody,
      String failureReason) {
    this.runId = runId;
    this.operationId = operationId;
    this.method = method;
    this.path = path;
    this.statusCode = statusCode;
    this.elapsedMs = elapsedMs;
    this.schemaValid = schemaValid;
    this.outcome = outcome;
    this.passed = "PASS".equals(outcome);
    this.requestJson = requestJson;
    this.responseBody = responseBody;
    this.failureReason = failureReason;
  }
}
