package com.inzent.airestclient.ai;

import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.AnalysisDtos.AuthAnalysis;
import com.inzent.airestclient.dto.AnalysisDtos.DependencyInfo;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.TestDtos.AiDiagnosisOutput;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * API 키 없이 전체 흐름을 구동하기 위한 규칙 기반 구현.
 * LLM을 호출하지 않고 HTTP 메서드·경로 규칙만으로 결정적인 결과를 만든다(보고서에 provider=fake로 표시).
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "fake", matchIfMissing = true)
public class FakeAiClient implements AiClient {

  @Override
  public String provider() {
    return "fake";
  }

  @Override
  public String model() {
    return "rule-based";
  }

  @Override
  public AiAnalysisOutput analyze(SpecAnalysisInput input) {
    List<EndpointInfo> endpoints = input.endpoints();

    List<EndpointInsight> insights = endpoints.stream().map(ep -> new EndpointInsight(
        ep.operationId(),
        ep.operationId(),
        ep.summary() != null && !ep.summary().isBlank() ? ep.summary() : ep.method() + " " + ep.path(),
        riskOf(ep),
        ep.secured() ? "인증 헤더가 필요합니다." : "")).toList();

    long reads = endpoints.stream().filter(ep -> riskOf(ep) == RiskLevel.SAFE).count();
    long deletes = endpoints.stream().filter(ep -> riskOf(ep) == RiskLevel.DESTRUCTIVE).count();
    long writes = endpoints.size() - reads - deletes;
    String summary = "%s: 총 %d개 엔드포인트(조회 %d, 생성/수정 %d, 삭제 %d). 규칙 기반 분석 결과입니다."
        .formatted(input.title(), endpoints.size(), reads, writes, deletes);

    boolean anySecured = endpoints.stream().anyMatch(EndpointInfo::secured);
    boolean anyPublic = endpoints.stream().anyMatch(ep -> !ep.secured());
    String scheme = input.securitySchemes().isEmpty() ? "" : input.securitySchemes().get(0).name();
    String authDescription = !anySecured ? "인증이 필요하지 않습니다."
        : "인증이 필요한 엔드포인트가 있습니다" + (scheme.isEmpty() ? "." : "(" + scheme + ")." )
            + (anyPublic ? " 일부 조회 API는 인증 없이 호출할 수 있습니다." : "");

    List<String> cautions = new ArrayList<>();
    if (deletes > 0) {
      cautions.add("삭제 API %d개는 대상 서버의 데이터를 실제로 제거합니다.".formatted(deletes));
    }
    if (writes > 0) {
      cautions.add("생성/수정 API %d개는 대상 서버의 상태를 변경합니다. 운영 서버 대상 테스트는 피하세요.".formatted(writes));
    }

    return new AiAnalysisOutput(summary, new AuthAnalysis(anySecured, scheme, authDescription),
        insights, dependencies(endpoints), cautions);
  }

  /** AI가 없으므로 빈 결과를 반환한다. 호출 측(SampleResolver)이 스키마 기반 규칙 생성기로 대체한다. */
  @Override
  public AiSampleOutput generateSamples(SampleGenerationInput input) {
    return new AiSampleOutput(List.of());
  }

  /** 상태 코드와 실패 메시지 규칙으로 원인을 분석한다(LLM 호출 없음). */
  @Override
  public AiDiagnosisOutput diagnose(DiagnosisInput input) {
    var cases = input.failures().stream().map(RuleBasedDiagnosis::diagnose).toList();
    return new AiDiagnosisOutput("실패 " + cases.size() + "건을 상태 코드와 오류 메시지 규칙으로 분석했습니다.", cases);
  }

  private static RiskLevel riskOf(EndpointInfo ep) {
    return switch (ep.method()) {
      case "DELETE" -> RiskLevel.DESTRUCTIVE;
      case "POST", "PUT", "PATCH" -> RiskLevel.MUTATING;
      default -> RiskLevel.SAFE;
    };
  }

  /** 경로 변수({id})가 있는 엔드포인트는, 부모 컬렉션 경로의 POST(생성)에 의존한다고 본다. */
  private static List<DependencyInfo> dependencies(List<EndpointInfo> endpoints) {
    Map<String, EndpointInfo> createByPath = endpoints.stream()
        .filter(ep -> ep.method().equals("POST") && !ep.path().contains("{"))
        .collect(Collectors.toMap(EndpointInfo::path, ep -> ep, (a, b) -> a));

    List<DependencyInfo> result = new ArrayList<>();
    for (EndpointInfo ep : endpoints) {
      int brace = ep.path().indexOf('{');
      if (brace < 0) {
        continue;
      }
      String collection = ep.path().substring(0, brace).replaceAll("/$", "");
      EndpointInfo creator = createByPath.get(collection);
      if (creator != null && !creator.operationId().equals(ep.operationId())) {
        result.add(new DependencyInfo(creator.operationId(), ep.operationId(),
            "경로 변수에 사용할 ID는 " + creator.operationId() + "로 생성한 리소스에서 얻습니다."));
      }
    }
    return result;
  }
}
