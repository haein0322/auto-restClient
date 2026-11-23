package com.inzent.airestclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.SampleData;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.AnalysisDtos.ProgressEvent;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.SecuritySchemeInfo;
import com.inzent.airestclient.dto.SpecDtos.SpecDetail;
import com.inzent.airestclient.dto.TestDtos.TestDefaults;
import com.inzent.airestclient.dto.TestDtos.TestOptions;
import com.inzent.airestclient.dto.TestDtos.TestRunSummary;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import com.inzent.airestclient.testrun.TestExecutor;
import com.inzent.airestclient.testrun.TestExecutor.AuthHeader;
import com.inzent.airestclient.testrun.TestExecutor.CaseOutcome;
import com.inzent.airestclient.testrun.TestExecutor.Plan;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 5단계: 통신 테스트 오케스트레이션. 사전 조건 확인·옵션 검증·입력 수집({@link #prepare})과
 * 실제 호출·저장({@link #execute})을 분리해, 컨트롤러가 검증 오류를 스트림 시작 전에 일반 JSON으로 응답할 수 있게 한다.
 * 호출이 길어질 수 있어 트랜잭션을 걸지 않고, 저장만 {@link TestRunStore}가 처리한다.
 */
@Service
public class TestRunService {

  /** 실행 준비가 끝난 입력. execute()에 그대로 전달한다. */
  public record Prepared(Long specId, Plan plan) {}

  private final SpecService specService;
  private final AnalysisResultStore analysisStore;
  private final GeneratedClientStore clientStore;
  private final SampleStore sampleStore;
  private final TestRunStore runStore;
  private final TestExecutor executor;
  private final Set<String> allowedHosts;
  private final String mockBaseUrl;
  private final String mockApiKey;

  public TestRunService(SpecService specService, AnalysisResultStore analysisStore,
      GeneratedClientStore clientStore, SampleStore sampleStore, TestRunStore runStore, TestExecutor executor,
      @Value("${app.test.allowed-hosts:localhost,127.0.0.1,[::1]}") String allowedHosts,
      @Value("${app.mock.base-url:http://localhost:${server.port:8090}/mock}") String mockBaseUrl,
      @Value("${app.mock.api-key:demo-key}") String mockApiKey) {
    this.specService = specService;
    this.analysisStore = analysisStore;
    this.clientStore = clientStore;
    this.sampleStore = sampleStore;
    this.runStore = runStore;
    this.executor = executor;
    this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(h -> h.trim().toLowerCase(Locale.ROOT))
        .filter(h -> !h.isEmpty()).collect(Collectors.toSet());
    this.mockBaseUrl = mockBaseUrl;
    this.mockApiKey = mockApiKey;
  }

  /** 입력란 기본값: 명세의 첫 서버 주소(없으면 내장 Mock). 내장 Mock이면 공개 데모 키도 채운다. */
  public TestDefaults defaults(Long specId) {
    SpecDetail detail = specService.detail(specId);
    String baseUrl = detail.servers().isEmpty() ? mockBaseUrl : detail.servers().get(0);
    boolean builtIn = baseUrl.startsWith(mockBaseUrl);
    return new TestDefaults(baseUrl, builtIn ? mockApiKey : "", builtIn);
  }

  public Prepared prepare(Long specId, TestOptions requested) {
    ApiSpec spec = specService.getOrThrow(specId);
    if (spec.getStatus().ordinal() < SpecStatus.SAMPLES_READY.ordinal()) {
      throw new BusinessException(ErrorCode.STEP_NOT_READY, "샘플 데이터를 먼저 생성해야 통신 테스트를 실행할 수 있습니다.");
    }
    TestOptions options = validateOptions(requested);

    AnalysisReport report = analysisStore.latest(specId).report();
    SpecDetail detail = specService.detail(specId);

    Map<String, EndpointInfo> endpoints = new LinkedHashMap<>();
    detail.endpoints().forEach(ep -> endpoints.put(ep.operationId(), ep));
    Map<String, RiskLevel> risks = report.endpoints().stream()
        .collect(Collectors.toMap(EndpointInsight::operationId, EndpointInsight::risk, (a, b) -> a));

    // 검증을 통과한 샘플만 실행 대상이다.
    Map<String, JsonNode> samples = new LinkedHashMap<>();
    for (SampleData row : sampleStore.findAll(specId)) {
      if (row.isValid() && row.getRequestJson() != null) {
        samples.put(row.getOperationId(), readJson(row.getRequestJson()));
      }
    }

    OpenApiSchemaValidator validator;
    try {
      validator = OpenApiSchemaValidator.fromContent(spec.getContent());
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "명세를 읽지 못했습니다.", e);
    }

    Plan plan = new Plan(report.testOrder(), endpoints, risks, report.dependencies(), samples,
        clientStore.latestPayload(specId), validator, options, authHeader(detail.securitySchemes()));
    return new Prepared(specId, plan);
  }

  public TestRunView execute(Prepared prepared, Consumer<ProgressEvent> progress) {
    int total = prepared.plan().testOrder().size();
    progress.accept(new ProgressEvent("PREPARE", "생성된 클라이언트를 컴파일하는 중...", 5));

    List<CaseOutcome> outcomes = executor.execute(prepared.plan(), (done, operationId) -> {
      int percent = 10 + (total == 0 ? 0 : 85 * done / total);
      progress.accept(operationId == null
          ? new ProgressEvent("SAVE", "결과를 저장하는 중...", 95)
          : new ProgressEvent("CALL", "호출 중: " + operationId + " (" + (done + 1) + "/" + total + ")", percent));
    });

    TestRunView view = runStore.save(prepared.specId(), prepared.plan().options().baseUrl(), outcomes);
    progress.accept(new ProgressEvent("DONE", "통신 테스트가 완료되었습니다.", 100));
    return view;
  }

  public TestRunView get(Long runId) {
    return runStore.get(runId);
  }

  public List<TestRunSummary> list(Long specId) {
    specService.getOrThrow(specId);
    return runStore.list(specId);
  }

  // ---------------- 내부 ----------------

  /** Base URL 형식과 허용 호스트를 검증한다. 허용 호스트 밖이면 명시적 확인이 필요하다. */
  private TestOptions validateOptions(TestOptions requested) {
    if (requested == null || requested.baseUrl() == null || requested.baseUrl().isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "대상 서버 주소(Base URL)를 입력해주세요.");
    }
    String baseUrl = requested.baseUrl().trim().replaceAll("/+$", "");
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "Base URL 형식이 올바르지 않습니다.", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "http/https 주소만 사용할 수 있습니다.");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (!allowedHosts.contains(host) && !requested.confirmExternal()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST,
          "허용된 호스트(" + String.join(", ", allowedHosts) + ") 밖의 서버입니다. 실제 서버에 요청이 전송되므로 '외부 서버 호출 확인'을 선택해야 실행할 수 있습니다.");
    }
    return new TestOptions(baseUrl, requested.apiKey(), requested.allowDestructive(), requested.confirmExternal(),
        requested.timeoutSeconds());
  }

  /** 명세의 보안 스킴에서 지원하는 인증 헤더(apiKey 헤더, bearer)를 찾는다. */
  private static AuthHeader authHeader(List<SecuritySchemeInfo> schemes) {
    for (SecuritySchemeInfo scheme : schemes) {
      String type = scheme.type() == null ? "" : scheme.type().toLowerCase(Locale.ROOT);
      String location = scheme.location() == null ? "" : scheme.location().toLowerCase(Locale.ROOT);
      if (type.equals("apikey") && location.equals("header") && scheme.keyName() != null) {
        return new AuthHeader(scheme.keyName(), "");
      }
      if (type.equals("http") && "bearer".equalsIgnoreCase(scheme.keyName())) {
        return new AuthHeader("Authorization", "Bearer ");
      }
    }
    return null;
  }

  private JsonNode readJson(String json) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 샘플을 읽지 못했습니다.", e);
    }
  }
}
