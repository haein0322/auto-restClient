package com.inzent.airestclient.generator;

import java.util.Set;

/** 생성 코드에 쓰이는 Java 식별자 규칙. 외부(명세·AI)에서 온 이름을 항상 유효한 식별자로 바꾼다. */
public final class JavaNaming {

  public static final Set<String> KEYWORDS = Set.of(
      "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
      "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
      "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
      "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
      "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
      "volatile", "while", "true", "false", "null", "var", "record", "yield");

  /** 모델 클래스 이름으로 쓰면 java.lang / 자주 쓰는 JDK 타입 / 생성 클래스와 충돌하는 이름 */
  private static final Set<String> RESERVED_TYPES = Set.of(
      "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
      "Character", "Number", "Void", "Class", "Record", "Enum", "Error", "Exception", "Throwable",
      "Override", "Deprecated", "Math", "System", "Thread", "Runnable", "Iterable", "Comparable",
      "Process", "Package", "Module", "List", "Map", "Set", "UUID", "BigDecimal", "BigInteger",
      "OffsetDateTime", "LocalDate", "Optional", "ApiClient", "ApiException", "MediaType", "HttpMethod",
      "RestClient", "HttpStatusCode", "HttpRequest", "ClientHttpResponse", "ParameterizedTypeReference",
      "JsonProperty", "JsonInclude", "JsonIgnoreProperties", "JsonValue", "IOException", "StandardCharsets");

  private JavaNaming() {}

  /** get_items_id, list-products → getItemsId, listProducts. 숫자로 시작하거나 키워드면 접두·접미를 붙인다. */
  public static String toCamelCase(String raw) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char c : raw.toCharArray()) {
      if (Character.isLetterOrDigit(c) && c < 128) {
        sb.append(sb.length() == 0 ? Character.toLowerCase(c) : (upper ? Character.toUpperCase(c) : c));
        upper = false;
      } else {
        upper = sb.length() > 0;
      }
    }
    String name = sb.toString();
    if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
      name = "call" + (name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }
    return KEYWORDS.contains(name) ? name + "Call" : name;
  }

  /** order-item → OrderItem. 예약된 타입 이름이면 Model 접미사를 붙인다. */
  public static String toClassName(String raw) {
    String camel = toCamelCase(raw);
    String pascal = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    return RESERVED_TYPES.contains(pascal) ? pascal + "Model" : pascal;
  }

  public static String toFieldName(String raw) {
    return toCamelCase(raw);
  }

  /** "in-stock" → IN_STOCK. 비어 있거나 숫자로 시작하면 V_ 접두어. */
  public static String toEnumConstant(String raw) {
    StringBuilder sb = new StringBuilder();
    for (char c : raw.toCharArray()) {
      if (Character.isLetterOrDigit(c) && c < 128) {
        sb.append(Character.toUpperCase(c));
      } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
        sb.append('_');
      }
    }
    String name = sb.toString().replaceAll("_$", "");
    if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
      name = "V_" + name;
    }
    return name;
  }

  /** 주석에 넣어도 컴파일을 깨뜨리지 않도록 정리한다(주석 종료, 유니코드 이스케이프, 줄바꿈 처리). */
  public static String javadoc(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("*/", "* /").replace("\\", "/").replaceAll("\\s+", " ").trim();
  }

  /** 문자열 리터럴에 안전하게 넣기 위한 이스케이프 */
  public static String stringLiteral(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
  }
}
