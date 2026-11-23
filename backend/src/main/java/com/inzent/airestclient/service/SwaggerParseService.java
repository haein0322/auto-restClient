package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.ParamInfo;
import com.inzent.airestclient.dto.SpecDtos.SecuritySchemeInfo;
import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Swagger 2.0 / OpenAPI 3.x 명세를 파싱하고 엔드포인트 요약 정보를 추출한다.
 * 보안: 외부 $ref(파일/URL)를 허용하면 서버의 로컬 파일 읽기나 외부 요청이 발생할 수 있어 거부한다.
 */
@Service
public class SwaggerParseService {

  /** 파싱 결과(영속화 전 중간 모델) */
  public record ParsedSpec(
      String title,
      String version,
      String description,
      List<String> servers,
      List<SecuritySchemeInfo> securitySchemes,
      List<EndpointInfo> endpoints,
      int schemaCount,
      List<String> warnings) {}

  private static final YAMLMapper YAML = new YAMLMapper();

  /** 이 문구가 포함된 파서 메시지는 경고가 아니라 명세 오류로 취급한다. */
  private static final List<String> FATAL_PATTERNS =
      List.of("unresolvable reference", "is missing", "is not of type");

  /** 파싱 직후의 OpenAPI 모델과 파서 메시지 */
  private record LoadedModel(OpenAPI api, List<String> messages) {}

  /** 코드 생성처럼 OpenAPI 모델 자체가 필요한 곳에서 사용한다(검증 규칙은 parse와 동일). */
  public OpenAPI parseModel(String content) {
    return load(content).api();
  }

  public ParsedSpec parse(String content) {
    LoadedModel loaded = load(content);
    OpenAPI api = loaded.api();
    List<String> warnings = new ArrayList<>(loaded.messages());
    List<EndpointInfo> endpoints = extractEndpoints(api, warnings);
    if (endpoints.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "정의된 엔드포인트가 없습니다.");
    }

    String title = api.getInfo() != null && api.getInfo().getTitle() != null
        ? api.getInfo().getTitle() : "(제목 없음)";
    String version = api.getInfo() != null ? api.getInfo().getVersion() : null;
    String description = api.getInfo() != null ? api.getInfo().getDescription() : null;
    int schemaCount = api.getComponents() != null && api.getComponents().getSchemas() != null
        ? api.getComponents().getSchemas().size() : 0;

    return new ParsedSpec(title, version, description, extractServers(api),
        extractSecuritySchemes(api), endpoints, schemaCount, warnings);
  }

  private LoadedModel load(String content) {
    if (content == null || content.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "명세 내용이 비어 있습니다.");
    }
    rejectExternalRefs(content);

    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    SwaggerParseResult result;
    try {
      result = new OpenAPIParser().readContents(content, null, options);
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "명세 파싱 중 오류가 발생했습니다.", e);
    }

    List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
    OpenAPI api = result.getOpenAPI();
    if (api == null) {
      throw new BusinessException(ErrorCode.INVALID_SPEC,
          messages.isEmpty() ? ErrorCode.INVALID_SPEC.defaultMessage() : String.join("; ", messages));
    }
    List<String> fatal = messages.stream()
        .filter(m -> FATAL_PATTERNS.stream().anyMatch(m::contains))
        .toList();
    if (!fatal.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, String.join("; ", fatal));
    }
    return new LoadedModel(api, messages);
  }

  /** 명세 안의 모든 $ref가 문서 내부("#...")를 가리키는지 검사한다. 형식 오류도 함께 걸러낸다. */
  private void rejectExternalRefs(String content) {
    JsonNode root;
    try {
      root = YAML.readTree(content);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INVALID_SPEC,
          "명세 형식(JSON/YAML)이 올바르지 않습니다: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "명세의 최상위는 객체여야 합니다.");
    }
    Deque<JsonNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      JsonNode node = stack.pop();
      if (node.isObject()) {
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
          throw new BusinessException(ErrorCode.INVALID_SPEC,
              "외부 참조($ref)는 지원하지 않습니다: " + ref.asText());
        }
      }
      node.forEach(stack::push);
    }
  }

  private List<EndpointInfo> extractEndpoints(OpenAPI api, List<String> warnings) {
    List<EndpointInfo> endpoints = new ArrayList<>();
    if (api.getPaths() == null) {
      return endpoints;
    }
    Set<String> usedIds = new HashSet<>();
    boolean globalSecured = api.getSecurity() != null && !api.getSecurity().isEmpty();

    for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
      String path = pathEntry.getKey();
      PathItem item = pathEntry.getValue();
      for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
        String method = opEntry.getKey().name();
        Operation op = opEntry.getValue();

        String operationId = uniqueOperationId(op.getOperationId(), method, path, usedIds, warnings);
        boolean secured = op.getSecurity() != null ? !op.getSecurity().isEmpty() : globalSecured;
        RequestBody body = OpenApiSupport.resolveRequestBody(api, op.getRequestBody());
        List<String> responseCodes = op.getResponses() == null
            ? List.of() : new ArrayList<>(op.getResponses().keySet());
        String tag = op.getTags() == null || op.getTags().isEmpty() ? "default" : op.getTags().get(0);

        endpoints.add(new EndpointInfo(operationId, method, path, op.getSummary(), tag, secured,
            mergeParameters(api, item.getParameters(), op.getParameters()),
            body != null, body != null && Boolean.TRUE.equals(body.getRequired()), responseCodes));
      }
    }
    return endpoints;
  }

  /** operationId가 없거나 중복이면 메서드+경로로 유일한 값을 만든다(코드 생성 시 메서드명으로 쓰이므로). */
  private String uniqueOperationId(String declared, String method, String path, Set<String> used,
      List<String> warnings) {
    String base = declared != null && !declared.isBlank()
        ? declared
        : method.toLowerCase() + "_" + path.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
    if (declared == null || declared.isBlank()) {
      warnings.add("operationId가 없어 자동 생성했습니다: " + method + " " + path + " → " + base);
    }
    String candidate = base;
    int seq = 2;
    while (!used.add(candidate)) {
      candidate = base + "_" + seq++;
    }
    if (!candidate.equals(base)) {
      warnings.add("operationId가 중복되어 변경했습니다: " + base + " → " + candidate);
    }
    return candidate;
  }

  private List<ParamInfo> mergeParameters(OpenAPI api, List<Parameter> pathLevel,
      List<Parameter> opLevel) {
    return OpenApiSupport.mergedParameters(api, pathLevel, opLevel).stream()
        .map(p -> new ParamInfo(p.getName(), p.getIn(),
            Boolean.TRUE.equals(p.getRequired()) || "path".equals(p.getIn()), typeOf(p.getSchema())))
        .toList();
  }

  private String typeOf(Schema<?> schema) {
    if (schema == null) {
      return "string";
    }
    if (schema.get$ref() != null) {
      return OpenApiSupport.simpleName(schema.get$ref());
    }
    return schema.getType() != null ? schema.getType() : "object";
  }

  private List<String> extractServers(OpenAPI api) {
    if (api.getServers() == null) {
      return List.of();
    }
    return api.getServers().stream().map(Server::getUrl).toList();
  }

  private List<SecuritySchemeInfo> extractSecuritySchemes(OpenAPI api) {
    if (api.getComponents() == null || api.getComponents().getSecuritySchemes() == null) {
      return List.of();
    }
    List<SecuritySchemeInfo> list = new ArrayList<>();
    api.getComponents().getSecuritySchemes().forEach((name, scheme) -> list.add(
        new SecuritySchemeInfo(name,
            scheme.getType() == null ? null : scheme.getType().toString(),
            scheme.getIn() == null ? null : scheme.getIn().toString(),
            scheme.getType() == SecurityScheme.Type.APIKEY ? scheme.getName() : scheme.getScheme())));
    return list;
  }
}
