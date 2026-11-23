package com.inzent.airestclient.generator;

import java.util.List;
import java.util.TreeSet;

/**
 * 생성기는 편의상 타입을 완전한 이름(FQN)으로 쓴다. 이 클래스가 허용 목록의 FQN을 단순 이름으로 바꾸고
 * import 문을 만들어, 사람이 읽기 좋은 소스 파일로 조립한다.
 */
final class JavaSource {

  private static final List<String> IMPORTABLE = List.of(
      "java.io.IOException",
      "java.math.BigDecimal",
      "java.nio.charset.StandardCharsets",
      "java.time.LocalDate",
      "java.time.OffsetDateTime",
      "java.util.List",
      "java.util.Map",
      "java.util.Optional",
      "java.util.UUID",
      "com.fasterxml.jackson.annotation.JsonIgnoreProperties",
      "com.fasterxml.jackson.annotation.JsonInclude",
      "com.fasterxml.jackson.annotation.JsonProperty",
      "com.fasterxml.jackson.annotation.JsonValue",
      "org.springframework.core.ParameterizedTypeReference",
      "org.springframework.http.HttpMethod",
      "org.springframework.http.HttpRequest",
      "org.springframework.http.HttpStatusCode",
      "org.springframework.http.MediaType",
      "org.springframework.http.client.ClientHttpResponse",
      "org.springframework.web.client.RestClient");

  private JavaSource() {}

  static String build(String packageName, String body, String... extraImports) {
    String simplified = body;
    TreeSet<String> imports = new TreeSet<>(List.of(extraImports));
    for (String fqn : IMPORTABLE) {
      if (simplified.contains(fqn)) {
        simplified = simplified.replace(fqn, fqn.substring(fqn.lastIndexOf('.') + 1));
        imports.add(fqn);
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(packageName).append(";\n\n");
    for (String fqn : imports) {
      sb.append("import ").append(fqn).append(";\n");
    }
    if (!imports.isEmpty()) {
      sb.append('\n');
    }
    return sb.append(simplified).toString();
  }
}
