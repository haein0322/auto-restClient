package com.inzent.airestclient.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 스키마를 JSON Schema 형태의 JsonNode로 바꾼다.
 * - {@link #plain}: 검증용. $ref는 그대로 두고 swagger 모델의 잡음 필드만 제거한다.
 * - {@link #expanded}: AI 프롬프트용. $ref를 펼치고 샘플 작성에 필요한 키워드만 남긴다.
 */
final class SampleSchemaView {

  private static final Set<String> KEEP = Set.of("type", "format", "enum", "properties", "required", "items",
      "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
      "minItems", "maxItems", "description", "example", "default", "additionalProperties", "allOf", "oneOf",
      "anyOf", "nullable", "$ref");
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final int MAX_DEPTH = 4;
  private static final int MAX_DESCRIPTION = 100;

  private SampleSchemaView() {}

  static JsonNode plain(Schema<?> schema) {
    return clean(Json.mapper().valueToTree(schema), null, 0, false);
  }

  static JsonNode expanded(OpenAPI api, Schema<?> schema) {
    return clean(Json.mapper().valueToTree(schema), api, 0, true);
  }

  private static JsonNode clean(JsonNode node, OpenAPI api, int depth, boolean inline) {
    if (node.isArray()) {
      ArrayNode array = JSON.arrayNode();
      node.forEach(child -> array.add(clean(child, api, depth, inline)));
      return array;
    }
    if (!node.isObject()) {
      return node;
    }

    JsonNode ref = node.get("$ref");
    if (inline && ref != null && ref.isTextual()) {
      Schema<?> target = OpenApiSupport.componentSchema(api, OpenApiSupport.simpleName(ref.asText()));
      if (target == null || depth >= MAX_DEPTH) {
        return JSON.objectNode().put("type", "object").put("description", "(" + OpenApiSupport.simpleName(ref.asText()) + ")");
      }
      return clean(Json.mapper().valueToTree(target), api, depth + 1, true);
    }

    ObjectNode result = JSON.objectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!KEEP.contains(field.getKey())) {
        continue;
      }
      JsonNode value = field.getValue();
      if (field.getKey().equals("description") && value.isTextual() && value.asText().length() > MAX_DESCRIPTION) {
        result.put("description", value.asText().substring(0, MAX_DESCRIPTION) + "…");
      } else if (field.getKey().equals("properties") && value.isObject()) {
        // 속성 이름은 키워드가 아니므로 필터를 적용하지 않고 각 속성 스키마만 정리한다.
        ObjectNode properties = JSON.objectNode();
        value.fields().forEachRemaining(p -> properties.set(p.getKey(), clean(p.getValue(), api, depth, inline)));
        result.set("properties", properties);
      } else {
        result.set(field.getKey(), clean(value, api, depth, inline));
      }
    }
    return result;
  }
}
