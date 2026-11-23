package com.inzent.airestclient.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * OpenAPI 명세 안의 스키마로 JSON 데이터를 검증한다.
 * 스키마 노드를 JSON Schema 문서로 취급하고, 명세의 components를 함께 넣어
 * "#/components/schemas/..." 형태의 $ref가 해석되도록 한다.
 * (샘플 데이터 검증, Mock 계약 테스트, 통신 테스트 응답 검증에서 공통 사용)
 */
public class OpenApiSchemaValidator {

  private static final YAMLMapper YAML = new YAMLMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

  private final JsonNode spec;

  public OpenApiSchemaValidator(JsonNode spec) {
    this.spec = spec;
  }

  /** YAML 또는 JSON 문자열(JSON은 YAML의 부분집합)로부터 생성한다. */
  public static OpenApiSchemaValidator fromContent(String content) throws IOException {
    return new OpenApiSchemaValidator(YAML.readTree(content));
  }

  /** 지정한 경로/메서드/상태코드의 JSON 응답 스키마를 찾는다. 응답이 $ref여도 해석한다. */
  public Optional<JsonNode> responseSchema(String path, String method, int statusCode) {
    JsonNode response = spec.path("paths").path(path).path(method.toLowerCase())
        .path("responses").path(String.valueOf(statusCode));
    response = resolveRef(response);
    JsonNode schema = response.path("content").path("application/json").path("schema");
    return schema.isMissingNode() ? Optional.empty() : Optional.of(schema);
  }

  /** 명세가 해당 상태 코드의 응답을 선언했는지(정확한 코드, 2XX 형태, default 순으로 확인). */
  public boolean declaresResponse(String path, String method, int statusCode) {
    JsonNode responses = spec.path("paths").path(path).path(method.toLowerCase()).path("responses");
    return responses.has(String.valueOf(statusCode))
        || responses.has((statusCode / 100) + "XX") || responses.has((statusCode / 100) + "xx")
        || responses.has("default");
  }

  /** 지정한 경로/메서드의 JSON 요청 본문 스키마를 찾는다. */
  public Optional<JsonNode> requestSchema(String path, String method) {
    JsonNode body = resolveRef(
        spec.path("paths").path(path).path(method.toLowerCase()).path("requestBody"));
    JsonNode schema = body.path("content").path("application/json").path("schema");
    return schema.isMissingNode() ? Optional.empty() : Optional.of(schema);
  }

  /** 데이터가 스키마를 만족하는지 검증하고, 위반 사항 목록을 반환한다(빈 목록이면 통과). */
  public List<String> validate(JsonNode schema, JsonNode data) {
    if (!(schema instanceof ObjectNode objectSchema)) {
      return List.of("스키마가 객체 형식이 아닙니다.");
    }
    ObjectNode root = objectSchema.deepCopy();
    JsonNode components = spec.get("components");
    if (components != null) {
      root.set("components", components.deepCopy()); // 변환이 원본 명세를 바꾸지 않도록 복사
    }
    normalizeOpenApi30(root);
    return FACTORY.getSchema(root).validate(data).stream()
        .map(ValidationMessage::getMessage)
        .sorted()
        .toList();
  }

  /**
   * OpenAPI 3.0 스키마 방언을 검증기가 쓰는 JSON Schema draft-07로 바꾼다.
   * - exclusiveMinimum/Maximum: 불리언 → 숫자(minimum/maximum 값을 옮김)
   * - nullable: true → type에 "null" 추가
   */
  private static void normalizeOpenApi30(JsonNode node) {
    if (node.isArray()) {
      node.forEach(OpenApiSchemaValidator::normalizeOpenApi30);
      return;
    }
    if (!node.isObject()) {
      return;
    }
    ObjectNode object = (ObjectNode) node;
    convertExclusive(object, "exclusiveMinimum", "minimum");
    convertExclusive(object, "exclusiveMaximum", "maximum");
    JsonNode type = object.get("type");
    if (object.path("nullable").asBoolean(false) && type != null && type.isTextual()) {
      ArrayNode types = object.arrayNode();
      types.add(type.asText()).add("null");
      object.set("type", types);
    }
    object.forEach(OpenApiSchemaValidator::normalizeOpenApi30);
  }

  private static void convertExclusive(ObjectNode object, String key, String limitKey) {
    JsonNode value = object.get(key);
    if (value == null || !value.isBoolean()) {
      return;
    }
    if (value.asBoolean() && object.has(limitKey)) {
      object.set(key, object.get(limitKey));
      object.remove(limitKey);
    } else {
      object.remove(key);
    }
  }

  private JsonNode resolveRef(JsonNode node) {
    JsonNode ref = node.get("$ref");
    if (ref == null || !ref.isTextual() || !ref.asText().startsWith("#/")) {
      return node;
    }
    return spec.at(ref.asText().substring(1));
  }
}
