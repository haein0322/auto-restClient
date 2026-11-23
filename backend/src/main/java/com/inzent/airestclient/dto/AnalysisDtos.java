package com.inzent.airestclient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 명세 분석의 입출력 DTO.
 * AI 구조화 출력 스키마가 이 타입들에서 자동 유도되므로 @JsonPropertyDescription이 곧 AI에게 주는 필드 설명이다.
 */
public final class AnalysisDtos {

  private AnalysisDtos() {}

  /** 호출 위험도. 통신 테스트에서 실행 허용 여부를 판단하는 기준이 된다. */
  public enum RiskLevel {
    SAFE,
    MUTATING,
    DESTRUCTIVE
  }

  public record AuthAnalysis(
      @JsonPropertyDescription("인증이 필요한 엔드포인트가 하나라도 있는지 여부") boolean required,
      @JsonPropertyDescription("인증 방식 이름(예: ApiKeyAuth). 없으면 빈 문자열") String scheme,
      @JsonPropertyDescription("클라이언트 개발자가 알아야 할 인증 사용법을 한국어 1~2문장으로 설명") String description) {}

  public record EndpointInsight(
      @JsonPropertyDescription("입력으로 받은 operationId. 반드시 입력에 존재하는 값을 그대로 사용") String operationId,
      @JsonPropertyDescription("Java 메서드명 제안. 영문 camelCase, 동사로 시작(예: listProducts)") String suggestedMethodName,
      @JsonPropertyDescription("이 엔드포인트의 업무 목적을 한국어 한 문장으로 설명") String purpose,
      @JsonPropertyDescription("SAFE(조회), MUTATING(생성/수정), DESTRUCTIVE(삭제/되돌릴 수 없는 변경)") RiskLevel risk,
      @JsonPropertyDescription("호출 시 주의사항(선행 호출, 제약, 부작용). 없으면 빈 문자열") String notes) {}

  public record DependencyInfo(
      @JsonPropertyDescription("먼저 호출해야 하는 엔드포인트의 operationId") String fromOperationId,
      @JsonPropertyDescription("fromOperationId의 결과(ID 등)가 필요한 엔드포인트의 operationId") String toOperationId,
      @JsonPropertyDescription("의존 이유를 한국어로 간단히 설명") String reason) {}

  /** AI가 반환하는 분석 결과(서버 검증 전). */
  public record AiAnalysisOutput(
      @JsonPropertyDescription("API 전체를 한국어 2~3문장으로 요약") String summary,
      AuthAnalysis authentication,
      @JsonPropertyDescription("입력된 모든 엔드포인트에 대해 하나씩") List<EndpointInsight> endpoints,
      @JsonPropertyDescription("엔드포인트 간 호출 선후관계(생성 후 조회 등)") List<DependencyInfo> dependencies,
      @JsonPropertyDescription("통신 테스트 시 유의할 점을 한국어로 최대 5개") List<String> cautions) {}

  /** 서버 검증을 거쳐 저장·표시되는 최종 분석 보고서. */
  public record AnalysisReport(
      String provider,
      String model,
      String summary,
      AuthAnalysis authentication,
      List<EndpointInsight> endpoints,
      List<DependencyInfo> dependencies,
      List<String> testOrder,
      List<String> cautions,
      List<String> warnings) {}

  public record AnalysisView(Long specId, LocalDateTime analyzedAt, AnalysisReport report) {}

  /** SSE로 전달되는 진행 상황 */
  public record ProgressEvent(String step, String message, int percent) {}
}
