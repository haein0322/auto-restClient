package com.inzent.airestclient.testrun;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.DependencyInfo;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.ClientDtos.ClientPayload;
import com.inzent.airestclient.dto.ClientDtos.GeneratedFile;
import com.inzent.airestclient.dto.ClientDtos.MethodSpec;
import com.inzent.airestclient.dto.ClientDtos.ParamSpec;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.TestDtos.TestOptions;
import com.inzent.airestclient.generator.InMemoryCompiler;
import com.inzent.airestclient.generator.InMemoryCompiler.CompileResult;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import com.inzent.airestclient.testrun.CaseEvaluator.Evaluation;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 3단계에서 생성한 클라이언트를 메모리에서 컴파일·로딩해, 4단계 샘플로 실제 HTTP 호출을 수행한다.
 * 생성된 클라이언트는 본문만 반환하므로, RestClient 인터셉터로 실제 상태 코드와 원본 응답 본문을 가로채 명세와 대조한다.
 */
@Component
public class TestExecutor {

  private static final Logger log = LoggerFactory.getLogger(TestExecutor.class);
  private static final int MAX_BODY_BYTES = 1024 * 1024;
  private static final int MAX_STORED_BODY = 8 * 1024;

  /** 한 번의 실행에 필요한 모든 입력 */
  public record Plan(
      List<String> testOrder,
      Map<String, EndpointInfo> endpoints,
      Map<String, RiskLevel> risks,
      List<DependencyInfo> dependencies,
      /** operationId → 유효한 샘플 요청 JSON (유효하지 않은 샘플은 포함하지 않는다) */
      Map<String, JsonNode> samples,
      ClientPayload client,
      OpenApiSchemaValidator validator,
      TestOptions options,
      AuthHeader auth) {}

  /** 인증 헤더 구성. valuePrefix 예: "Bearer " */
  public record AuthHeader(String name, String valuePrefix) {}

  /** @param outcome PASS | FAIL | SKIPPED */
  public record CaseOutcome(String operationId, String method, String path, String outcome, Integer statusCode,
      Long elapsedMs, Boolean schemaValid, JsonNode request, String responseBody, String failureReason) {}

  /** 인터셉터가 마지막 호출에서 관측한 상태 코드와 원본 본문 */
  private static final class Capture {

    volatile Integer status;
    volatile String body;

    void reset() {
      status = null;
      body = null;
    }
  }

  private final InMemoryCompiler compiler;
  private final ObjectMapper mapper;

  public TestExecutor(InMemoryCompiler compiler, ObjectMapper mapper) {
    this.compiler = compiler;
    this.mapper = mapper;
  }

  /** @param progress (완료한 개수, 현재 operationId) 알림 */
  public List<CaseOutcome> execute(Plan plan, BiConsumer<Integer, String> progress) {
    ClientPayload client = plan.client();
    if (client.methods() == null) {
      throw new BusinessException(ErrorCode.STEP_NOT_READY,
          "이 클라이언트는 이전 버전으로 생성되어 통신 테스트에 필요한 정보가 없습니다. 3단계에서 코드를 다시 생성해주세요.");
    }

    CompileResult compiled = compiler.compile(javaSources(client.files()));
    if (!compiled.success()) {
      throw new BusinessException(ErrorCode.GENERATION_FAILED,
          "생성된 클라이언트가 컴파일되지 않아 테스트를 실행할 수 없습니다. 3단계에서 코드를 다시 생성해주세요.");
    }
    ClassLoader loader = compiled.classes().newClassLoader(getClass().getClassLoader());
    Capture capture = new Capture();

    Object apiClient;
    Class<?> clientClass;
    try {
      clientClass = loader.loadClass(client.basePackage() + ".ApiClient");
      apiClient = clientClass.getConstructor(RestClient.class).newInstance(buildRestClient(plan, capture));
    } catch (ReflectiveOperationException e) {
      throw new BusinessException(ErrorCode.TEST_FAILED, "생성된 클라이언트를 로딩하지 못했습니다.", e);
    }

    Map<String, MethodSpec> methodsByKey = new HashMap<>();
    client.methods().forEach(m -> methodsByKey.put(m.key(), m));
    String runSuffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, JsonNode> createdIds = new HashMap<>();
    List<CaseOutcome> outcomes = new ArrayList<>();

    for (String operationId : plan.testOrder()) {
      EndpointInfo endpoint = plan.endpoints().get(operationId);
      if (endpoint == null) {
        continue;
      }
      progress.accept(outcomes.size(), operationId);
      CaseOutcome outcome = runCase(plan, endpoint, methodsByKey, clientClass, apiClient, capture, createdIds, runSuffix);
      outcomes.add(outcome);
      if ("PASS".equals(outcome.outcome()) && outcome.responseBody() != null) {
        try {
          JsonNode id = RequestPreparer.extractId(mapper.readTree(outcome.responseBody()));
          if (id != null) {
            createdIds.put(operationId, id);
          }
        } catch (IOException ignored) {
          // JSON이 아니면 체이닝 대상이 아니다.
        }
      }
    }
    progress.accept(outcomes.size(), null);
    return outcomes;
  }

  // ---------------- 케이스 실행 ----------------

  private CaseOutcome runCase(Plan plan, EndpointInfo ep, Map<String, MethodSpec> methodsByKey,
      Class<?> clientClass, Object apiClient, Capture capture, Map<String, JsonNode> createdIds, String runSuffix) {
    String id = ep.operationId();
    JsonNode sample = plan.samples().get(id);
    if (sample == null) {
      return skipped(ep, null, "유효한 샘플이 없습니다. 4단계에서 샘플을 생성하거나 수정하세요.");
    }
    RiskLevel risk = plan.risks().getOrDefault(id, RiskLevel.MUTATING);
    if (risk == RiskLevel.DESTRUCTIVE && !plan.options().allowDestructive()) {
      return skipped(ep, sample, "삭제 등 되돌릴 수 없는 호출이라 실행하지 않았습니다. 옵션에서 허용하면 실행됩니다.");
    }
    MethodSpec spec = methodsByKey.get(ep.method() + " " + ep.path());
    Method method = spec == null ? null : findMethod(clientClass, spec.methodName());
    if (method == null) {
      return skipped(ep, sample, "생성된 클라이언트에 이 엔드포인트의 메서드가 없습니다(지원하지 않는 요청 형식일 수 있음).");
    }

    // 의존 호출 중 가장 마지막에 성공한 응답의 id를 사용한다.
    JsonNode chainedId = null;
    for (DependencyInfo dep : plan.dependencies()) {
      if (dep.toOperationId().equals(id) && createdIds.containsKey(dep.fromOperationId())) {
        chainedId = createdIds.get(dep.fromOperationId());
      }
    }
    ObjectNode request = RequestPreparer.prepare(sample, chainedId, runSuffix);

    Object[] args;
    try {
      args = buildArgs(spec, request, method.getGenericParameterTypes());
    } catch (IllegalArgumentException e) {
      return new CaseOutcome(id, ep.method(), ep.path(), "FAIL", null, null, null, request, null,
          "샘플 값을 메서드 파라미터 타입으로 변환하지 못했습니다: " + e.getMessage());
    }

    capture.reset();
    long started = System.nanoTime();
    Evaluation evaluation;
    try {
      method.invoke(apiClient, args);
      evaluation = capture.status == null
          ? Evaluation.fail(null, null, "응답을 관측하지 못했습니다.")
          : CaseEvaluator.evaluate(plan.validator(), ep.path(), ep.method(), capture.status, capture.body);
    } catch (InvocationTargetException e) {
      evaluation = evaluateFailure(e.getCause(), plan.client().basePackage(), capture);
    } catch (IllegalAccessException e) {
      evaluation = Evaluation.fail(null, null, "클라이언트 메서드를 호출하지 못했습니다: " + e.getMessage());
    }
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;

    String stored = capture.body == null ? null
        : capture.body.length() > MAX_STORED_BODY ? capture.body.substring(0, MAX_STORED_BODY) + "…(생략)" : capture.body;
    return new CaseOutcome(id, ep.method(), ep.path(), evaluation.outcome(), evaluation.statusCode(), elapsedMs,
        evaluation.schemaValid(), request, stored, evaluation.failureReason());
  }

  /** 생성된 ApiException은 격리 ClassLoader의 클래스이므로 리플렉션으로 상태 코드·본문을 읽는다. */
  private Evaluation evaluateFailure(Throwable cause, String basePackage, Capture capture) {
    if (cause != null && cause.getClass().getName().equals(basePackage + ".ApiException")) {
      try {
        int status = (Integer) cause.getClass().getMethod("getStatusCode").invoke(cause);
        String body = (String) cause.getClass().getMethod("getResponseBody").invoke(cause);
        return CaseEvaluator.fromApiError(status, body);
      } catch (ReflectiveOperationException e) {
        log.warn("ApiException 정보를 읽지 못했습니다", e);
      }
    }
    return CaseEvaluator.fromException(cause == null ? new IllegalStateException("알 수 없는 오류") : cause, capture.status);
  }

  private CaseOutcome skipped(EndpointInfo ep, JsonNode request, String reason) {
    return new CaseOutcome(ep.operationId(), ep.method(), ep.path(), "SKIPPED", null, null, null, request, null, reason);
  }

  // ---------------- 인자 구성 / RestClient ----------------

  /** 샘플 요청을 메서드 시그니처 순서의 인자 배열로 변환한다. 샘플에 없는 선택 파라미터는 null. */
  private Object[] buildArgs(MethodSpec spec, JsonNode request, Type[] parameterTypes) {
    Object[] args = new Object[spec.params().size()];
    for (int i = 0; i < args.length; i++) {
      ParamSpec param = spec.params().get(i);
      JsonNode value = switch (param.in()) {
        case "path" -> request.path("pathParams").get(param.name());
        case "query" -> request.path("queryParams").get(param.name());
        case "header" -> request.path("headers").get(param.name());
        default -> request.get("body");
      };
      if (value == null || value.isNull()) {
        args[i] = null;
        continue;
      }
      JavaType type = mapper.getTypeFactory().constructType(parameterTypes[i]);
      args[i] = mapper.convertValue(value, type);
    }
    return args;
  }

  private RestClient buildRestClient(Plan plan, Capture capture) {
    int timeout = plan.options().timeoutSeconds() == null ? 15 : Math.max(1, Math.min(60, plan.options().timeoutSeconds()));
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    JdkClientHttpRequestFactory jdk = new JdkClientHttpRequestFactory(http);
    jdk.setReadTimeout(Duration.ofSeconds(timeout));

    RestClient.Builder builder = RestClient.builder()
        .baseUrl(plan.options().baseUrl())
        // 응답 본문을 여러 번 읽을 수 있게 버퍼링한다(인터셉터가 읽어도 클라이언트가 다시 읽을 수 있도록).
        .requestFactory(new BufferingClientHttpRequestFactory(jdk))
        .requestInterceptor((request, body, execution) -> {
          ClientHttpResponse response = execution.execute(request, body);
          capture.status = response.getStatusCode().value();
          capture.body = readBody(response);
          return response;
        });
    String apiKey = plan.options().apiKey();
    if (plan.auth() != null && apiKey != null && !apiKey.isBlank()) {
      builder.defaultHeader(plan.auth().name(), plan.auth().valuePrefix() + apiKey);
    }
    return builder.build();
  }

  private static String readBody(ClientHttpResponse response) throws IOException {
    byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static Method findMethod(Class<?> clientClass, String name) {
    for (Method method : clientClass.getDeclaredMethods()) {
      if (method.getName().equals(name) && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        return method;
      }
    }
    return null;
  }

  private static Map<String, String> javaSources(List<GeneratedFile> files) {
    Map<String, String> sources = new LinkedHashMap<>();
    for (GeneratedFile file : files) {
      if (file.path().endsWith(".java")) {
        sources.put(file.path().replace("src/main/java/", ""), file.content());
      }
    }
    return sources;
  }
}
