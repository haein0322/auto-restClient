package com.inzent.airestclient.testrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;

/**
 * 실행 직전에 샘플 요청을 보정한다. 원본 샘플은 바꾸지 않고 복사본을 만든다.
 * - 의존 호출의 응답 ID를 경로 파라미터에 주입(생성 → 조회/수정/삭제가 방금 만든 데이터를 대상으로 하도록)
 * - 중복 불가 값(이메일)을 실행마다 고유하게 만들어 반복 실행 시 409 충돌을 막는다.
 */
final class RequestPreparer {

  private static final Pattern EMAIL = Pattern.compile("^([^@\\s]+)@([^@\\s]+\\.[^@\\s]+)$");

  private RequestPreparer() {}

  /**
   * @param chainedId 선행 호출 응답의 id(없으면 null)
   * @param uniqueSuffix 실행마다 다른 짧은 문자열
   */
  static ObjectNode prepare(JsonNode sample, JsonNode chainedId, String uniqueSuffix) {
    ObjectNode request = sample.deepCopy() instanceof ObjectNode node ? node : new ObjectNode(
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);

    // 경로 파라미터가 정확히 하나일 때만 주입한다(어느 파라미터인지 모호하면 샘플 값을 유지).
    JsonNode pathParams = request.get("pathParams");
    if (chainedId != null && !chainedId.isNull() && pathParams instanceof ObjectNode params && params.size() == 1) {
      String name = params.fieldNames().next();
      params.set(name, chainedId);
    }

    JsonNode body = request.get("body");
    if (body != null) {
      request.set("body", uniquify(body, uniqueSuffix));
    }
    return request;
  }

  private static JsonNode uniquify(JsonNode node, String suffix) {
    if (node.isTextual()) {
      var matcher = EMAIL.matcher(node.asText());
      if (matcher.matches()) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(matcher.group(1) + "+" + suffix + "@" + matcher.group(2));
      }
      return node;
    }
    if (node instanceof ObjectNode object) {
      object.fieldNames().forEachRemaining(name -> object.set(name, uniquify(object.get(name), suffix)));
    } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, uniquify(array.get(i), suffix));
      }
    }
    return node;
  }

  /** 응답 본문(JSON)에서 체이닝에 쓸 id를 꺼낸다. 숫자/문자열 id만 허용한다. */
  static JsonNode extractId(JsonNode response) {
    if (response == null || !response.isObject()) {
      return null;
    }
    JsonNode id = response.get("id");
    return id != null && (id.isNumber() || id.isTextual()) ? id : null;
  }
}
