package com.inzent.airestclient.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.List;
import java.util.Map;

/**
 * 오퍼레이션 하나에 대한 규칙 기반 샘플 요청 {pathParams, queryParams, headers, body}을 만든다.
 * 쿼리·헤더 파라미터는 필수이거나 기본값/예시가 있는 것만 포함한다(선택 값은 비워 두는 것이 가장 안전한 기본).
 */
public class DefaultSampleBuilder {

  private final OpenAPI api;
  private final SchemaSampleGenerator generator;

  public DefaultSampleBuilder(OpenAPI api) {
    this.api = api;
    this.generator = new SchemaSampleGenerator(api);
  }

  public ObjectNode build(Operation operation, PathItem item) {
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    ObjectNode pathParams = JsonNodeFactory.instance.objectNode();
    ObjectNode queryParams = JsonNodeFactory.instance.objectNode();
    ObjectNode headers = JsonNodeFactory.instance.objectNode();

    for (Parameter p : declaredParameters(operation, item)) {
      boolean include = "path".equals(p.getIn()) || Boolean.TRUE.equals(p.getRequired())
          || (p.getSchema() != null && (p.getSchema().getDefault() != null || p.getSchema().getExample() != null));
      if (!include) {
        continue;
      }
      JsonNode value = generator.generate(p.getSchema(), p.getName());
      switch (p.getIn()) {
        case "path" -> pathParams.set(p.getName(), value);
        case "query" -> queryParams.set(p.getName(), value);
        case "header" -> headers.set(p.getName(), value);
        default -> { /* cookie 등은 지원하지 않는다 */ }
      }
    }
    putIfNotEmpty(request, "pathParams", pathParams);
    putIfNotEmpty(request, "queryParams", queryParams);
    putIfNotEmpty(request, "headers", headers);

    MediaType json = jsonBody(operation);
    if (json != null) {
      request.set("body", generator.generate(json.getSchema(), "body"));
    }
    return request;
  }

  /** 경로 공통 파라미터와 오퍼레이션 파라미터를 합쳐 반환한다. */
  public List<Parameter> declaredParameters(Operation operation, PathItem item) {
    return OpenApiSupport.mergedParameters(api, item.getParameters(), operation.getParameters());
  }

  public MediaType jsonBody(Operation operation) {
    RequestBody body = OpenApiSupport.resolveRequestBody(api, operation.getRequestBody());
    if (body == null || body.getContent() == null) {
      return null;
    }
    MediaType exact = body.getContent().get("application/json");
    if (exact != null) {
      return exact;
    }
    return body.getContent().entrySet().stream().filter(e -> e.getKey().contains("json"))
        .map(Map.Entry::getValue).findFirst().orElse(null);
  }

  private static void putIfNotEmpty(ObjectNode request, String key, ObjectNode value) {
    if (value.size() > 0) {
      request.set(key, value);
    }
  }
}
