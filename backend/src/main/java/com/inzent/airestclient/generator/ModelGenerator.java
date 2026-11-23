package com.inzent.airestclient.generator;

import com.inzent.airestclient.openapi.OpenApiSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 스키마를 Java 17 record / enum 소스로 변환하고, 스키마 → Java 타입 매핑을 제공한다.
 * 매핑 규칙: object → record, string enum → enum, array → List, additionalProperties → Map,
 * allOf → 속성 병합, oneOf/anyOf → Object(경고).
 */
class ModelGenerator {

  private static final int MAX_DEPTH = 12;

  private final OpenAPI api;
  private final String modelPackage;
  private final List<String> warnings;

  /** 클래스명 → 완성된 소스 */
  private final Map<String, String> sources = new LinkedHashMap<>();
  /** 컴포넌트 스키마 이름 → 클래스명 */
  private final Map<String, String> componentClass = new HashMap<>();
  private final Set<String> usedClassNames = new HashSet<>();

  ModelGenerator(OpenAPI api, String modelPackage, List<String> warnings) {
    this.api = api;
    this.modelPackage = modelPackage;
    this.warnings = warnings;
  }

  Map<String, String> sources() {
    return sources;
  }

  String modelPackage() {
    return modelPackage;
  }

  /** components.schemas 중 클래스가 필요한 것(객체, enum)을 모두 생성한다. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  void generateComponents() {
    if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = api.getComponents().getSchemas();
    for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
      if (isEnum(entry.getValue()) || isObjectLike(entry.getValue())) {
        ensureComponentClass(entry.getKey());
      }
    }
  }

  // ---------------- 타입 매핑 ----------------

  /**
   * 스키마의 Java 타입 문자열(JDK 타입은 FQN)을 반환한다. 인라인 객체/enum은 hint 이름으로 클래스를 만든다.
   */
  String typeOf(Schema<?> schema, String hint) {
    return typeOf(schema, hint, 0);
  }

  private String typeOf(Schema<?> schema, String hint, int depth) {
    if (schema == null) {
      return "Object";
    }
    if (depth > MAX_DEPTH) {
      warnings.add("스키마 중첩이 너무 깊어 Object로 처리했습니다: " + hint);
      return "Object";
    }

    String ref = schema.get$ref();
    if (ref != null) {
      String name = OpenApiSupport.simpleName(ref);
      Schema<?> target = OpenApiSupport.componentSchema(api, name);
      if (target == null) {
        warnings.add("존재하지 않는 스키마 참조를 Object로 처리했습니다: " + name);
        return "Object";
      }
      if (isEnum(target) || isObjectLike(target)) {
        return ensureComponentClass(name);
      }
      return typeOf(target, name, depth + 1); // 단순 별칭(예: 문자열/배열 스키마)은 펼친다
    }

    if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
      // allOf 멤버가 $ref 하나뿐이고 추가 속성이 없으면 그 타입을 그대로 사용
      if (schema.getAllOf().size() == 1 && isEmptyProperties(schema)) {
        return typeOf(schema.getAllOf().get(0), hint, depth + 1);
      }
      return registerInline(hint, schema);
    }
    if (hasItems(schema.getOneOf()) || hasItems(schema.getAnyOf())) {
      warnings.add("oneOf/anyOf는 지원하지 않아 Object로 처리했습니다: " + hint);
      return "Object";
    }
    if (isEnum(schema)) {
      return registerInline(hint, schema);
    }

    String type = schema.getType();
    if ("array".equals(type)) {
      return "java.util.List<" + typeOf(schema.getItems(), hint + "_item", depth + 1) + ">";
    }
    if ("object".equals(type) || (type == null && !isEmptyProperties(schema))) {
      if (!isEmptyProperties(schema)) {
        return registerInline(hint, schema);
      }
      Object additional = schema.getAdditionalProperties();
      if (additional instanceof Schema<?> valueSchema) {
        return "java.util.Map<String, " + typeOf(valueSchema, hint + "_value", depth + 1) + ">";
      }
      if (Boolean.TRUE.equals(additional)) {
        return "java.util.Map<String, Object>";
      }
      return registerInline(hint, schema); // 속성 없는 빈 객체
    }
    if (type == null) {
      return "Object";
    }
    return primitiveType(type, schema.getFormat());
  }

  private static String primitiveType(String type, String format) {
    return switch (type) {
      case "string" -> switch (format == null ? "" : format) {
        case "date-time" -> "java.time.OffsetDateTime";
        case "date" -> "java.time.LocalDate";
        case "uuid" -> "java.util.UUID";
        case "binary" -> "byte[]";
        default -> "String";
      };
      case "integer" -> "int64".equals(format) ? "Long" : "Integer";
      case "number" -> switch (format == null ? "" : format) {
        case "float" -> "Float";
        case "double" -> "Double";
        default -> "java.math.BigDecimal";
      };
      case "boolean" -> "Boolean";
      case "file" -> "byte[]";
      default -> "Object";
    };
  }

  // ---------------- 클래스 등록 ----------------

  private String ensureComponentClass(String schemaName) {
    String existing = componentClass.get(schemaName);
    if (existing != null) {
      return existing;
    }
    String className = uniqueClassName(schemaName);
    componentClass.put(schemaName, className); // 자기 참조 스키마의 무한 재귀 방지를 위해 먼저 등록
    Schema<?> schema = OpenApiSupport.componentSchema(api, schemaName);
    sources.put(className, isEnum(schema) ? enumSource(className, schema) : recordSource(className, schema));
    return className;
  }

  private String registerInline(String hint, Schema<?> schema) {
    String className = uniqueClassName(hint);
    sources.put(className, null); // 자리 선점(중첩 중 이름 충돌 방지)
    sources.put(className, isEnum(schema) ? enumSource(className, schema) : recordSource(className, schema));
    return className;
  }

  private String uniqueClassName(String raw) {
    String base = JavaNaming.toClassName(raw);
    String name = base;
    int seq = 2;
    while (!usedClassNames.add(name)) {
      name = base + seq++;
    }
    return name;
  }

  // ---------------- 소스 생성 ----------------

  private String recordSource(String className, Schema<?> schema) {
    Map<String, Schema<?>> properties = new LinkedHashMap<>();
    OpenApiSupport.collectProperties(api, schema, properties);

    List<String> components = new ArrayList<>();
    StringBuilder paramDocs = new StringBuilder();
    Set<String> usedFields = new HashSet<>();
    for (Map.Entry<String, Schema<?>> prop : properties.entrySet()) {
      String type = typeOf(prop.getValue(), className + "_" + prop.getKey());
      String field = uniqueName(JavaNaming.toFieldName(prop.getKey()), usedFields);
      components.add("    @com.fasterxml.jackson.annotation.JsonProperty(" + JavaNaming.stringLiteral(prop.getKey())
          + ") " + type + " " + field);
      String description = prop.getValue().getDescription();
      if (description != null && !description.isBlank()) {
        paramDocs.append(" * @param ").append(field).append(' ').append(JavaNaming.javadoc(description)).append('\n');
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("/**\n * ").append(docOf(schema, className)).append('\n');
    if (paramDocs.length() > 0) {
      sb.append(" *\n").append(paramDocs);
    }
    sb.append(" */\n");
    sb.append("@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)\n");
    sb.append("@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)\n");
    sb.append("public record ").append(className).append('(');
    if (!components.isEmpty()) {
      sb.append('\n').append(String.join(",\n", components)).append('\n');
    }
    sb.append(") {}\n");
    return JavaSource.build(modelPackage, sb.toString());
  }

  private String enumSource(String className, Schema<?> schema) {
    StringBuilder constants = new StringBuilder();
    Set<String> usedConstants = new HashSet<>();
    List<Object> values = new ArrayList<>(schema.getEnum());
    for (Object value : values) {
      if (value == null) {
        continue;
      }
      String raw = value.toString();
      String constant = uniqueName(JavaNaming.toEnumConstant(raw), usedConstants);
      if (constants.length() > 0) {
        constants.append(",\n");
      }
      constants.append("  ").append(constant).append('(').append(JavaNaming.stringLiteral(raw)).append(')');
    }
    String body = "/** " + docOf(schema, className) + " */\n"
        + "public enum " + className + " {\n"
        + constants + ";\n\n"
        + "  private final String value;\n\n"
        + "  " + className + "(String value) {\n    this.value = value;\n  }\n\n"
        + "  @com.fasterxml.jackson.annotation.JsonValue\n"
        + "  public String getValue() {\n    return value;\n  }\n\n"
        + "  @Override\n  public String toString() {\n    return value;\n  }\n}\n";
    return JavaSource.build(modelPackage, body);
  }

  private static String docOf(Schema<?> schema, String fallback) {
    String description = schema.getDescription();
    return description != null && !description.isBlank() ? JavaNaming.javadoc(description) : fallback;
  }

  private static String uniqueName(String base, Set<String> used) {
    String name = base;
    int seq = 2;
    while (!used.add(name)) {
      name = base + seq++;
    }
    return name;
  }

  // ---------------- 판별 ----------------

  private static boolean isEnum(Schema<?> schema) {
    return schema != null && schema.getEnum() != null && !schema.getEnum().isEmpty()
        && (schema.getType() == null || "string".equals(schema.getType()));
  }

  private static boolean isEmptyProperties(Schema<?> schema) {
    return schema.getProperties() == null || schema.getProperties().isEmpty();
  }

  private static boolean hasItems(List<?> list) {
    return list != null && !list.isEmpty();
  }

  /** record로 만들어야 하는 객체형 스키마인지(속성이 있거나 allOf, 또는 additionalProperties 없는 빈 object) */
  private static boolean isObjectLike(Schema<?> schema) {
    if (schema == null) {
      return false;
    }
    if (hasItems(schema.getAllOf())) {
      return true;
    }
    if (!isEmptyProperties(schema)) {
      return schema.getType() == null || "object".equals(schema.getType());
    }
    return "object".equals(schema.getType()) && schema.getAdditionalProperties() == null;
  }
}
