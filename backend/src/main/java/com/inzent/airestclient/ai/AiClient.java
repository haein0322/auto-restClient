package com.inzent.airestclient.ai;

import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.TestDtos.AiDiagnosisOutput;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.SecuritySchemeInfo;
import java.util.List;
import java.util.Map;

/**
 * AI 제공자 추상화. 구현체(Claude / 규칙 기반 Fake)는 설정(app.ai.provider)으로 교체된다.
 * 반환값은 신뢰하지 않으며, 호출하는 쪽(AnalysisService)이 반드시 검증·보정한다.
 */
public interface AiClient {

  /** 제공자 식별자(보고서에 기록): claude, fake */
  String provider();

  /** 사용 모델명(보고서에 기록) */
  String model();

  AiAnalysisOutput analyze(SpecAnalysisInput input);

  /**
   * 엔드포인트별 샘플 요청을 생성한다. feedback이 있으면 이전 시도의 검증 오류이므로 이를 고쳐서 다시 만든다.
   * 규칙 기반(fake) 구현은 빈 결과를 반환하며, 이때 호출 측이 결정적 생성기로 대체한다.
   */
  AiSampleOutput generateSamples(SampleGenerationInput input);

  /** 통신 테스트에서 실패한 케이스의 원인을 분석하고 해결 방법을 제안한다. */
  AiDiagnosisOutput diagnose(DiagnosisInput input);

  /** 분석 대상 명세의 요약 입력 */
  record SpecAnalysisInput(
      String title,
      String description,
      List<String> servers,
      List<SecuritySchemeInfo> securitySchemes,
      List<EndpointInfo> endpoints) {}

  /** 샘플 생성 대상 엔드포인트. schemaJson은 파라미터·본문 스키마를 $ref 없이 펼친 JSON 문자열. */
  record SampleTarget(String operationId, String method, String path, String schemaJson) {}

  /** @param feedback operationId → 직전 시도에서 발견된 검증 오류 목록 */
  record SampleGenerationInput(String title, List<SampleTarget> targets, Map<String, List<String>> feedback) {}

  /** 실패 케이스 하나. requestJson/responseSnippet은 길이가 제한된 일부이며 인증 정보는 포함하지 않는다. */
  record FailureInfo(String operationId, String method, String path, Integer statusCode, String failureReason,
      String requestJson, String responseSnippet) {}

  record DiagnosisInput(String title, List<FailureInfo> failures) {}
}
