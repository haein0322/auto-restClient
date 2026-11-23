package com.inzent.airestclient.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 스키마에서 제약(필수, enum, 범위, 길이, 형식)을 만족하는 샘플 JSON을 결정적으로 만든다.
 * Fake AI 모드의 생성기이자, AI 결과가 검증을 통과하지 못했을 때의 대체 수단이다.
 * 같은 입력이면 항상 같은 결과가 나온다(난수·시각 미사용).
 */
public class SchemaSampleGenerator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  /** 이 깊이부터는 필수가 아닌 속성을 생략하고 배열을 비워 순환 스키마가 커지는 것을 막는다. */
  private static final int SHALLOW_DEPTH = 3;
  private static final int MAX_DEPTH = 8;

  private final OpenAPI api;

  public SchemaSampleGenerator(OpenAPI api) {
    this.api = api;
  }

  public JsonNode generate(Schema<?> schema, String propertyName) {
    return generate(schema, propertyName, 0);
  }

  private JsonNode generate(Schema<?> schema, String name, int depth) {
    if (schema == null) {
      return JSON.textNode("sample");
    }
    if (depth > MAX_DEPTH) {
      return JSON.nullNode();
    }

    String ref = schema.get$ref();
    if (ref != null) {
      return generate(OpenApiSupport.componentSchema(api, OpenApiSupport.simpleName(ref)), name, depth + 1);
    }
    if (schema.getExample() != null) {
      return MAPPER.valueToTree(schema.getExample());
    }
    if (schema.getDefault() != null) {
      return MAPPER.valueToTree(schema.getDefault());
    }
    if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
      return MAPPER.valueToTree(schema.getEnum().get(0));
    }
    if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
      return generateObject(schema, depth);
    }
    if (hasItems(schema.getOneOf())) {
      return generate(schema.getOneOf().get(0), name, depth + 1);
    }
    if (hasItems(schema.getAnyOf())) {
      return generate(schema.getAnyOf().get(0), name, depth + 1);
    }

    String type = schema.getType();
    if ("array".equals(type)) {
      return generateArray(schema, name, depth);
    }
    if ("object".equals(type) || (type == null && schema.getProperties() != null)) {
      return generateObject(schema, depth);
    }
    if (type == null) {
      return JSON.textNode("sample");
    }
    return switch (type) {
      case "integer" -> integerSample(schema, name);
      case "number" -> numberSample(schema, name);
      case "boolean" -> JSON.booleanNode(true);
      default -> stringSample(schema, name);
    };
  }

  private JsonNode generateObject(Schema<?> schema, int depth) {
    Map<String, Schema<?>> properties = new LinkedHashMap<>();
    OpenApiSupport.collectProperties(api, schema, properties);

    ObjectNode node = JSON.objectNode();
    if (properties.isEmpty() && schema.getAdditionalProperties() instanceof Schema<?> valueSchema) {
      node.set("key", generate(valueSchema, "value", depth + 1));
      return node;
    }
    for (Map.Entry<String, Schema<?>> property : properties.entrySet()) {
      boolean required = isRequired(schema, property.getKey());
      if (depth >= SHALLOW_DEPTH && !required) {
        continue;
      }
      node.set(property.getKey(), generate(property.getValue(), property.getKey(), depth + 1));
    }
    return node;
  }

  /** allOf 멤버의 required까지 포함해 필수 여부를 판단한다. */
  private boolean isRequired(Schema<?> schema, String property) {
    if (schema.getRequired() != null && schema.getRequired().contains(property)) {
      return true;
    }
    if (schema.getAllOf() != null) {
      for (Schema<?> member : schema.getAllOf()) {
        Schema<?> resolved = member.get$ref() != null
            ? OpenApiSupport.componentSchema(api, OpenApiSupport.simpleName(member.get$ref())) : member;
        if (resolved != null && isRequired(resolved, property)) {
          return true;
        }
      }
    }
    return false;
  }

  private JsonNode generateArray(Schema<?> schema, String name, int depth) {
    ArrayNode array = JSON.arrayNode();
    if (depth >= SHALLOW_DEPTH && (schema.getMinItems() == null || schema.getMinItems() == 0)) {
      return array;
    }
    int count = Math.max(1, schema.getMinItems() == null ? 1 : schema.getMinItems());
    if (schema.getMaxItems() != null) {
      count = Math.min(count, schema.getMaxItems());
    }
    for (int i = 0; i < count; i++) {
      array.add(generate(schema.getItems(), name, depth + 1));
    }
    return array;
  }

  // ---------------- 스칼라 ----------------

  private JsonNode integerSample(Schema<?> schema, String name) {
    long base = isIdName(name) ? 1 : 10;
    return JSON.numberNode(clamp(BigDecimal.valueOf(base), schema).longValue());
  }

  private JsonNode numberSample(Schema<?> schema, String name) {
    BigDecimal clamped = clamp(BigDecimal.valueOf(isIdName(name) ? 1 : 10), schema);
    return JSON.numberNode(clamped);
  }

  /** minimum/maximum(및 exclusive)을 만족하도록 값을 보정한다. */
  private static BigDecimal clamp(BigDecimal value, Schema<?> schema) {
    BigDecimal result = value;
    BigDecimal min = schema.getMinimum();
    BigDecimal max = schema.getMaximum();
    if (min != null) {
      boolean exclusive = Boolean.TRUE.equals(schema.getExclusiveMinimum());
      BigDecimal floor = exclusive ? min.add(BigDecimal.ONE) : min;
      if (result.compareTo(floor) < 0) {
        result = floor;
      }
    }
    if (max != null) {
      boolean exclusive = Boolean.TRUE.equals(schema.getExclusiveMaximum());
      BigDecimal ceiling = exclusive ? max.subtract(BigDecimal.ONE) : max;
      if (result.compareTo(ceiling) > 0) {
        result = ceiling;
      }
    }
    return result;
  }

  private JsonNode stringSample(Schema<?> schema, String name) {
    String format = schema.getFormat() == null ? "" : schema.getFormat();
    String value = switch (format) {
      case "date-time" -> "2026-01-15T09:30:00Z";
      case "date" -> "2026-01-15";
      case "uuid" -> "123e4567-e89b-12d3-a456-426614174000";
      case "email" -> "sample@example.com";
      case "uri", "url" -> "https://example.com/sample";
      case "byte" -> "c2FtcGxl";
      default -> name != null && name.toLowerCase().contains("email") ? "sample@example.com"
          : "sample_" + (name == null ? "value" : name);
    };
    if (schema.getMinLength() != null && value.length() < schema.getMinLength()) {
      value = value + "x".repeat(schema.getMinLength() - value.length());
    }
    if (schema.getMaxLength() != null && value.length() > schema.getMaxLength()) {
      value = value.substring(0, schema.getMaxLength());
    }
    return JSON.textNode(value);
  }

  private static boolean isIdName(String name) {
    return name != null && (name.equals("id") || name.endsWith("Id") || name.endsWith("_id"));
  }

  private static boolean hasItems(java.util.List<?> list) {
    return list != null && !list.isEmpty();
  }
}
