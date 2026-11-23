package com.inzent.airestclient.generator;

import com.inzent.airestclient.dto.ClientDtos.MethodSpec;
import com.inzent.airestclient.dto.ClientDtos.ParamSpec;
import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring RestClient 기반 ApiClient / ApiException 소스를 만든다.
 * 오퍼레이션마다 메서드 하나(경로·쿼리·헤더 파라미터, JSON 본문, 2xx 응답 타입)를 생성하고,
 * 4xx/5xx는 ApiException(상태코드·본문 포함)으로 던진다.
 */
class ClientGenerator {

  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");
  private static final Set<String> BODY_VERBS = Set.of("POST", "PUT", "PATCH");
  private static final Set<String> SUPPORTED_VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  /** 생성될 메서드의 파라미터 */
  private record Param(String javaName, String javaType, String origName, String in, String doc) {}

  /** 인증 방식(apiKey 헤더 또는 Bearer) */
  private record Auth(String paramName, String headerName, String valuePrefix) {}

  private final OpenAPI api;
  private final ModelGenerator models;
  private final String basePackage;
  private final Map<String, String> methodNames;
  private final List<String> warnings;
  private final Set<String> usedMethodNames = new HashSet<>();
  private final List<MethodSpec> methodSpecs = new ArrayList<>();

  /** README 예시에 쓰는 첫 번째 생성 메서드 시그니처 */
  private String firstMethodExample;

  ClientGenerator(OpenAPI api, ModelGenerator models, String basePackage,
      Map<String, String> methodNames, List<String> warnings) {
    this.api = api;
    this.models = models;
    this.basePackage = basePackage;
    this.methodNames = methodNames;
    this.warnings = warnings;
  }

  String firstMethodExample() {
    return firstMethodExample;
  }

  /** 생성된 메서드의 시그니처 메타데이터(apiClientSource() 호출 이후에 채워진다). */
  List<MethodSpec> methodSpecs() {
    return methodSpecs;
  }

  String apiExceptionSource() {
    String body = """
        /** API가 4xx/5xx 응답을 반환했을 때 던져지는 예외. 상태 코드와 응답 본문을 담는다. */
        public class ApiException extends RuntimeException {

          private final int statusCode;
          private final String responseBody;

          public ApiException(int statusCode, String responseBody) {
            super("HTTP " + statusCode + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
          }

          public int getStatusCode() {
            return statusCode;
          }

          public String getResponseBody() {
            return responseBody;
          }
        }
        """;
    return JavaSource.build(basePackage, body);
  }

  String apiClientSource() {
    Auth auth = detectAuth();
    StringBuilder methods = new StringBuilder();
    if (api.getPaths() != null) {
      for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
        for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathEntry.getValue().readOperationsMap().entrySet()) {
          String method = generateMethod(pathEntry.getKey(), opEntry.getKey().name(), opEntry.getValue(),
              pathEntry.getValue());
          if (method != null) {
            methods.append(method).append('\n');
          }
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("/**\n * ").append(JavaNaming.javadoc(api.getInfo() != null && api.getInfo().getTitle() != null
        ? api.getInfo().getTitle() : "API")).append(" 클라이언트 (Spring RestClient 기반, 자동 생성)\n");
    if (api.getInfo() != null && api.getInfo().getVersion() != null) {
      sb.append(" * <p>명세 버전: ").append(JavaNaming.javadoc(api.getInfo().getVersion())).append('\n');
    }
    sb.append(" */\n");
    sb.append("public class ApiClient {\n\n");
    sb.append("  private final org.springframework.web.client.RestClient restClient;\n\n");
    sb.append("  /** 직접 구성한 RestClient(타임아웃, 인터셉터 등)를 사용할 때 */\n");
    sb.append("  public ApiClient(org.springframework.web.client.RestClient restClient) {\n");
    sb.append("    this.restClient = restClient;\n  }\n\n");
    sb.append("  public static ApiClient create(String baseUrl) {\n");
    sb.append("    return new ApiClient(org.springframework.web.client.RestClient.builder().baseUrl(baseUrl).build());\n  }\n\n");
    if (auth != null) {
      sb.append("  /** 인증 정보를 모든 요청의 기본 헤더(").append(auth.headerName()).append(")로 설정한다. */\n");
      sb.append("  public static ApiClient create(String baseUrl, String ").append(auth.paramName()).append(") {\n");
      sb.append("    org.springframework.web.client.RestClient.Builder builder =\n");
      sb.append("        org.springframework.web.client.RestClient.builder().baseUrl(baseUrl);\n");
      sb.append("    if (").append(auth.paramName()).append(" != null && !").append(auth.paramName()).append(".isBlank()) {\n");
      sb.append("      builder.defaultHeader(").append(JavaNaming.stringLiteral(auth.headerName())).append(", ")
          .append(auth.valuePrefix().isEmpty() ? "" : JavaNaming.stringLiteral(auth.valuePrefix()) + " + ")
          .append(auth.paramName()).append(");\n    }\n");
      sb.append("    return new ApiClient(builder.build());\n  }\n\n");
    }
    sb.append(methods);
    sb.append("  private static void throwError(org.springframework.http.HttpRequest request,\n");
    sb.append("      org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {\n");
    sb.append("    String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);\n");
    sb.append("    throw new ApiException(response.getStatusCode().value(), body);\n  }\n}\n");
    return JavaSource.build(basePackage, sb.toString(), models.modelPackage() + ".*");
  }

  // ---------------- 메서드 생성 ----------------

  private String generateMethod(String path, String verb, Operation op, PathItem item) {
    String label = verb + " " + path;
    if (!SUPPORTED_VERBS.contains(verb)) {
      warnings.add(label + ": 지원하지 않는 HTTP 메서드라 건너뛰었습니다.");
      return null;
    }

    String methodName = uniqueMethodName(methodNames.get(label), label);
    String hint = methodName;

    // --- 파라미터 ---
    List<Parameter> declared = OpenApiSupport.mergedParameters(api, item.getParameters(), op.getParameters());
    Set<String> usedNames = new HashSet<>(Set.of("body"));
    List<Param> pathParams = new ArrayList<>();
    List<Param> queryParams = new ArrayList<>();
    List<Param> headerParams = new ArrayList<>();
    List<String> pathOrder = new ArrayList<>();
    Matcher matcher = PATH_VARIABLE.matcher(path);
    while (matcher.find()) {
      pathOrder.add(matcher.group(1));
    }
    for (Parameter p : declared) {
      String in = p.getIn();
      if ("cookie".equals(in)) {
        warnings.add(label + ": 쿠키 파라미터 '" + p.getName() + "'는 지원하지 않아 제외했습니다.");
        continue;
      }
      String name = uniqueParamName(JavaNaming.toFieldName(p.getName()), usedNames);
      Param param = new Param(name, models.typeOf(p.getSchema(), hint + "_" + p.getName()), p.getName(), in,
          p.getDescription() == null ? "" : JavaNaming.javadoc(p.getDescription()));
      switch (in) {
        case "path" -> pathParams.add(param);
        case "query" -> queryParams.add(param);
        case "header" -> headerParams.add(param);
        default -> warnings.add(label + ": 알 수 없는 파라미터 위치(" + in + ")를 제외했습니다.");
      }
    }
    // 경로 변수는 build(Object...)에 템플릿 등장 순서대로 전달해야 한다.
    List<Param> orderedPathParams = new ArrayList<>();
    for (String variable : pathOrder) {
      Param found = pathParams.stream().filter(p -> p.origName().equals(variable)).findFirst().orElse(null);
      if (found == null) {
        String name = uniqueParamName(JavaNaming.toFieldName(variable), usedNames);
        found = new Param(name, "String", variable, "path", "");
        warnings.add(label + ": 경로 변수 '" + variable + "'가 파라미터로 선언되지 않아 String으로 처리했습니다.");
      }
      orderedPathParams.add(found);
    }

    // --- 요청 본문 ---
    String bodyType = null;
    RequestBody requestBody = OpenApiSupport.resolveRequestBody(api, op.getRequestBody());
    if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
      MediaType json = jsonMediaType(requestBody.getContent());
      if (json == null) {
        warnings.add(label + ": JSON이 아닌 요청 본문(" + String.join(", ", requestBody.getContent().keySet())
            + ")은 지원하지 않아 이 오퍼레이션을 건너뛰었습니다.");
        return null;
      }
      if (!BODY_VERBS.contains(verb)) {
        warnings.add(label + ": " + verb + " 요청 본문은 지원하지 않아 본문을 제외했습니다.");
      } else {
        bodyType = models.typeOf(json.getSchema(), hint + "_request");
      }
    }

    // --- 응답 ---
    ReturnInfo returnInfo = returnInfo(op, hint);

    // --- 시그니처 ---
    List<String> signature = new ArrayList<>();
    List<ParamSpec> specs = new ArrayList<>();
    StringBuilder paramDocs = new StringBuilder();
    for (Param p : orderedPathParams) {
      signature.add(p.javaType() + " " + p.javaName());
      specs.add(new ParamSpec("path", p.origName()));
      paramDocs.append("   * @param ").append(p.javaName()).append(' ').append(docOrName(p)).append('\n');
    }
    if (bodyType != null) {
      signature.add(bodyType + " body");
      specs.add(new ParamSpec("body", "body"));
      paramDocs.append("   * @param body 요청 본문\n");
    }
    for (Param p : queryParams) {
      signature.add(p.javaType() + " " + p.javaName());
      specs.add(new ParamSpec("query", p.origName()));
      paramDocs.append("   * @param ").append(p.javaName()).append(' ').append(docOrName(p)).append(" (쿼리, 생략하려면 null)\n");
    }
    for (Param p : headerParams) {
      signature.add(p.javaType() + " " + p.javaName());
      specs.add(new ParamSpec("header", p.origName()));
      paramDocs.append("   * @param ").append(p.javaName()).append(' ').append(docOrName(p)).append(" (헤더, 생략하려면 null)\n");
    }

    StringBuilder sb = new StringBuilder();
    String summary = op.getSummary() != null && !op.getSummary().isBlank()
        ? JavaNaming.javadoc(op.getSummary()) : methodName;
    sb.append("  /**\n   * ").append(summary).append('\n');
    if (op.getDescription() != null && !op.getDescription().isBlank()) {
      sb.append("   * <p>").append(JavaNaming.javadoc(op.getDescription())).append('\n');
    }
    sb.append("   * <p><code>").append(verb).append(' ').append(JavaNaming.javadoc(path)).append("</code>\n");
    if (paramDocs.length() > 0) {
      sb.append("   *\n").append(paramDocs);
    }
    sb.append("   * @throws ApiException 4xx/5xx 응답일 때\n   */\n");

    sb.append("  public ").append(returnInfo.type()).append(' ').append(methodName).append('(')
        .append(String.join(", ", signature)).append(") {\n");

    String chain = requestChain(verb, path, orderedPathParams, queryParams, headerParams, bodyType != null, returnInfo);
    sb.append(returnInfo.isVoid() ? "    " : "    return ").append(chain).append(";\n  }\n");

    methodSpecs.add(new MethodSpec(label, methodName, specs));
    if (firstMethodExample == null) {
      firstMethodExample = (returnInfo.isVoid() ? "" : returnInfo.type() + " result = ") + "client." + methodName
          + "(" + String.join(", ", signature.stream().map(s -> "...").toList()) + ");";
    }
    return sb.toString();
  }

  private String requestChain(String verb, String path, List<Param> pathParams, List<Param> queryParams,
      List<Param> headerParams, boolean hasBody, ReturnInfo returnInfo) {
    StringBuilder sb = new StringBuilder("restClient." + verb.toLowerCase() + "()\n");
    sb.append("        .uri(uriBuilder -> uriBuilder\n");
    sb.append("            .path(").append(JavaNaming.stringLiteral(path)).append(")\n");
    for (Param p : queryParams) {
      sb.append("            .queryParamIfPresent(").append(JavaNaming.stringLiteral(p.origName()))
          .append(", java.util.Optional.ofNullable(").append(p.javaName()).append("))\n");
    }
    sb.append("            .build(").append(String.join(", ", pathParams.stream().map(Param::javaName).toList()))
        .append("))\n");
    if (!headerParams.isEmpty()) {
      sb.append("        .headers(headers -> {\n");
      for (Param p : headerParams) {
        sb.append("          if (").append(p.javaName()).append(" != null) {\n");
        sb.append("            headers.set(").append(JavaNaming.stringLiteral(p.origName())).append(", String.valueOf(")
            .append(p.javaName()).append("));\n          }\n");
      }
      sb.append("        })\n");
    }
    if (hasBody) {
      sb.append("        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)\n");
      sb.append("        .body(body)\n");
    }
    sb.append("        .retrieve()\n");
    sb.append("        .onStatus(org.springframework.http.HttpStatusCode::isError, ApiClient::throwError)\n");
    if (returnInfo.isVoid()) {
      sb.append("        .toBodilessEntity()");
    } else if (returnInfo.type().contains("<")) {
      sb.append("        .body(new org.springframework.core.ParameterizedTypeReference<").append(returnInfo.type())
          .append(">() {})");
    } else {
      sb.append("        .body(").append(returnInfo.type()).append(".class)");
    }
    return sb.toString();
  }

  // ---------------- 응답/본문 해석 ----------------

  private record ReturnInfo(String type) {
    boolean isVoid() {
      return type.equals("void");
    }
  }

  /** 가장 낮은 번호의 2xx 응답 중 본문이 있는 것의 타입. 없으면 void. */
  private ReturnInfo returnInfo(Operation op, String hint) {
    if (op.getResponses() == null) {
      return new ReturnInfo("void");
    }
    Map<String, ApiResponse> success = new TreeMap<>();
    op.getResponses().forEach((code, response) -> {
      if (code.matches("2\\d\\d")) {
        success.put(code, response);
      }
    });
    for (ApiResponse raw : success.values()) {
      ApiResponse response = OpenApiSupport.resolveResponse(api, raw);
      Content content = response == null ? null : response.getContent();
      if (content == null || content.isEmpty()) {
        continue;
      }
      MediaType json = jsonMediaType(content);
      if (json != null) {
        return new ReturnInfo(models.typeOf(json.getSchema(), hint + "_response"));
      }
      boolean text = content.keySet().stream().anyMatch(k -> k.startsWith("text/"));
      return new ReturnInfo(text ? "String" : "byte[]");
    }
    return new ReturnInfo("void");
  }

  private static MediaType jsonMediaType(Content content) {
    MediaType exact = content.get("application/json");
    if (exact != null) {
      return exact;
    }
    return content.entrySet().stream().filter(e -> e.getKey().contains("json"))
        .map(Map.Entry::getValue).findFirst().orElse(null);
  }

  // ---------------- 이름/인증 ----------------

  private String uniqueMethodName(String proposed, String label) {
    String base = proposed != null && !proposed.isBlank() ? proposed : JavaNaming.toCamelCase(label);
    String name = base;
    int seq = 2;
    while (!usedMethodNames.add(name)) {
      name = base + seq++;
    }
    return name;
  }

  private static String uniqueParamName(String base, Set<String> used) {
    String name = base;
    int seq = 2;
    while (!used.add(name)) {
      name = base + seq++;
    }
    return name;
  }

  private static String docOrName(Param p) {
    return p.doc().isEmpty() ? p.origName() : p.doc();
  }

  /** 지원하는 인증 방식: apiKey(헤더) 또는 http bearer. 첫 번째 일치 항목을 사용한다. */
  private Auth detectAuth() {
    if (api.getComponents() == null || api.getComponents().getSecuritySchemes() == null) {
      return null;
    }
    for (SecurityScheme scheme : api.getComponents().getSecuritySchemes().values()) {
      if (scheme.getType() == SecurityScheme.Type.APIKEY && scheme.getIn() == SecurityScheme.In.HEADER
          && scheme.getName() != null) {
        return new Auth("apiKey", scheme.getName(), "");
      }
      if (scheme.getType() == SecurityScheme.Type.HTTP && "bearer".equalsIgnoreCase(scheme.getScheme())) {
        return new Auth("bearerToken", "Authorization", "Bearer ");
      }
    }
    warnings.add("지원하지 않는 인증 방식입니다(apiKey 헤더, bearer만 지원). ApiClient(RestClient) 생성자로 직접 구성하세요.");
    return null;
  }
}
