package com.inzent.airestclient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 샘플 데이터(4단계) DTO.
 * 샘플 요청 JSON 형태: {"pathParams":{...}, "queryParams":{...}, "headers":{...}, "body":{...}}
 * (해당 없는 키는 생략). 7단계 통신 테스트가 이 구조를 그대로 사용한다.
 */
public final class SampleDtos {

  private SampleDtos() {}

  // ---------------- AI 구조화 출력 ----------------
  // 본문은 임의 구조의 JSON이라 구조화 출력 스키마로 표현할 수 없어, 문자열(bodyJson)로 받아 서버가 파싱·검증한다.

  public record NameValue(
      @JsonPropertyDescription("파라미터 이름") String name,
      @JsonPropertyDescription("파라미터 값. 숫자·불리언도 문자열로 표기(예: \"1\", \"true\")") String value) {}

  public record AiSampleItem(
      @JsonPropertyDescription("입력으로 받은 operationId 그대로") String operationId,
      @JsonPropertyDescription("경로 파라미터 값. 없으면 빈 배열") List<NameValue> pathParams,
      @JsonPropertyDescription("쿼리 파라미터 값. 필수이거나 샘플에 포함할 가치가 있는 것만. 없으면 빈 배열") List<NameValue> queryParams,
      @JsonPropertyDescription("헤더 파라미터 값(인증 헤더 제외). 없으면 빈 배열") List<NameValue> headers,
      @JsonPropertyDescription("요청 본문 JSON을 문자열로 직렬화한 값. 본문이 없는 엔드포인트는 빈 문자열") String bodyJson) {}

  public record AiSampleOutput(
      @JsonPropertyDescription("입력된 모든 엔드포인트에 대해 하나씩") List<AiSampleItem> samples) {}

  // ---------------- API 응답/요청 ----------------

  /**
   * @param source RULE(규칙 기반), AI, FALLBACK(AI 결과가 검증을 통과하지 못해 규칙 기반으로 대체), USER(직접 편집)
   */
  public record SampleItem(
      String operationId,
      String method,
      String path,
      JsonNode request,
      boolean valid,
      List<String> errors,
      String source,
      LocalDateTime updatedAt) {}

  public record SampleSetView(
      Long specId, String provider, int validCount, int total, List<String> warnings, List<SampleItem> samples) {}

  public record UpdateSampleRequest(JsonNode request) {}
}
