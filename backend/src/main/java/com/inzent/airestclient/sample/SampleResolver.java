package com.inzent.airestclient.sample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inzent.airestclient.ai.AiClient;
import com.inzent.airestclient.ai.AiClient.SampleGenerationInput;
import com.inzent.airestclient.ai.AiClient.SampleTarget;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.dto.SampleDtos.AiSampleItem;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.SampleDtos.NameValue;
import com.inzent.airestclient.openapi.OpenApiSupport;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 샘플 생성의 핵심 로직. AI가 만든 샘플을 서버가 명세로 검증하고, 위반이 있으면 오류를 되돌려 다시 생성시키며(최대 3회),
 * 끝내 통과하지 못한 항목은 스키마 기반 규칙 생성기로 대체한다. AI가 없으면(fake) 처음부터 규칙 생성기를 쓴다.
 */
@Component
public class SampleResolver {

  private static final Logger log = LoggerFactory.getLogger(SampleResolver.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  /** 최초 1회 + 검증 오류 피드백 재시도 2회 */
  private static final int MAX_AI_ROUNDS = 3;
  private static final int BATCH_SIZE = 20;

  public record Target(String operationId, String method, String path, Operation operation, PathItem item) {}

  /** @param source RULE, AI, FALLBACK */
  public record Resolved(String operationId, JsonNode request, boolean valid, List<String> errors, String source) {}

  public record Result(List<Resolved> samples, List<String> warnings) {}

  private final AiClient aiClient;

  public SampleResolver(AiClient aiClient) {
    this.aiClient = aiClient;
  }

  public String provider() {
    return aiClient.provider();
  }

  public Result resolve(OpenAPI api, OpenApiSchemaValidator schemaValidator, String title, List<Target> targets) {
    DefaultSampleBuilder builder = new DefaultSampleBuilder(api);
    SampleValidator validator = new SampleValidator(schemaValidator, builder);
    Map<String, Resolved> resolved = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    List<Target> pending = new ArrayList<>(targets);
    boolean useAi = !"fake".equals(aiClient.provider());

    if (useAi) {
      pending = askAiWithFeedback(api, builder, validator, title, pending, resolved, warnings);
      if (!pending.isEmpty() && warnings.isEmpty()) {
        warnings.add("AI 샘플 " + pending.size() + "개가 검증을 통과하지 못해 규칙 기반 생성으로 대체했습니다.");
      }
    }

    String fallbackSource = useAi ? "FALLBACK" : "RULE";
    for (Target target : pending) {
      ObjectNode request = builder.build(target.operation(), target.item());
      List<String> errors = validator.validate(target.path(), target.method(), target.operation(), target.item(), request);
      resolved.put(target.operationId(), new Resolved(target.operationId(), request, errors.isEmpty(), errors, fallbackSource));
    }

    List<Resolved> ordered = targets.stream().map(t -> resolved.get(t.operationId())).toList();
    return new Result(ordered, warnings);
  }

  /** AI 호출 → 검증 → 오류 피드백 재호출 루프. 통과하지 못한 대상 목록을 반환한다. */
  private List<Target> askAiWithFeedback(OpenAPI api, DefaultSampleBuilder builder, SampleValidator validator,
      String title, List<Target> initial, Map<String, Resolved> resolved, List<String> warnings) {
    List<Target> pending = initial;
    Map<String, List<String>> feedback = new HashMap<>();

    for (int round = 0; round < MAX_AI_ROUNDS && !pending.isEmpty(); round++) {
      Map<String, AiSampleItem> items = new HashMap<>();
      try {
        for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
          List<Target> batch = pending.subList(from, Math.min(from + BATCH_SIZE, pending.size()));
          AiSampleOutput output = aiClient.generateSamples(new SampleGenerationInput(title,
              batch.stream().map(t -> describe(api, builder, t)).toList(), feedback));
          if (output != null && output.samples() != null) {
            output.samples().stream().filter(i -> i != null && i.operationId() != null)
                .forEach(i -> items.putIfAbsent(i.operationId(), i));
          }
        }
      } catch (BusinessException e) {
        log.warn("AI 샘플 생성 실패(round {}): {}", round, e.getMessage());
        warnings.add("AI 호출에 실패해 규칙 기반 생성으로 대체했습니다: " + e.getMessage());
        return pending;
      }

      List<Target> stillPending = new ArrayList<>();
      Map<String, List<String>> nextFeedback = new HashMap<>();
      for (Target target : pending) {
        AiSampleItem item = items.get(target.operationId());
        if (item == null) {
          nextFeedback.put(target.operationId(), List.of("응답에 이 operationId의 샘플이 없습니다."));
          stillPending.add(target);
          continue;
        }
        Parsed parsed = toRequest(api, item, builder.declaredParameters(target.operation(), target.item()));
        List<String> errors = new ArrayList<>(parsed.errors());
        if (errors.isEmpty()) {
          errors.addAll(validator.validate(target.path(), target.method(), target.operation(), target.item(), parsed.request()));
        }
        if (errors.isEmpty()) {
          resolved.put(target.operationId(), new Resolved(target.operationId(), parsed.request(), true, List.of(), "AI"));
        } else {
          nextFeedback.put(target.operationId(), errors);
          stillPending.add(target);
        }
      }
      pending = stillPending;
      feedback = nextFeedback;
    }
    return pending;
  }

  // ---------------- AI 입력/출력 변환 ----------------

  /** AI에게 줄 엔드포인트 설명: 파라미터와 본문 스키마를 $ref 없이 펼친 JSON */
  private SampleTarget describe(OpenAPI api, DefaultSampleBuilder builder, Target target) {
    ObjectNode description = JSON.objectNode();
    for (String in : List.of("path", "query", "header")) {
      ArrayNode params = JSON.arrayNode();
      for (Parameter p : builder.declaredParameters(target.operation(), target.item())) {
        if (!in.equals(p.getIn())) {
          continue;
        }
        ObjectNode node = JSON.objectNode();
        node.put("name", p.getName());
        node.put("required", "path".equals(in) || Boolean.TRUE.equals(p.getRequired()));
        if (p.getSchema() != null) {
          node.set("schema", SampleSchemaView.expanded(api, p.getSchema()));
        }
        params.add(node);
      }
      if (params.size() > 0) {
        description.set(in + "Params", params);
      }
    }
    MediaType json = builder.jsonBody(target.operation());
    if (json != null && json.getSchema() != null) {
      description.set("body", SampleSchemaView.expanded(api, json.getSchema()));
    }
    return new SampleTarget(target.operationId(), target.method(), target.path(), description.toString());
  }

  private record Parsed(ObjectNode request, List<String> errors) {}

  /** AI의 이름/값 목록과 bodyJson 문자열을 샘플 요청 JSON으로 변환한다(파라미터 값은 스키마 타입에 맞게 변환). */
  private Parsed toRequest(OpenAPI api, AiSampleItem item, List<Parameter> declared) {
    ObjectNode request = JSON.objectNode();
    List<String> errors = new ArrayList<>();
    putParams(api, request, "pathParams", item.pathParams(), "path", declared);
    putParams(api, request, "queryParams", item.queryParams(), "query", declared);
    putParams(api, request, "headers", item.headers(), "header", declared);

    String bodyJson = item.bodyJson();
    if (bodyJson != null && !bodyJson.isBlank()) {
      try {
        request.set("body", MAPPER.readTree(bodyJson));
      } catch (JsonProcessingException e) {
        errors.add("bodyJson이 올바른 JSON이 아닙니다: " + e.getOriginalMessage());
      }
    }
    return new Parsed(request, errors);
  }

  private void putParams(OpenAPI api, ObjectNode request, String key, List<NameValue> values, String in,
      List<Parameter> declared) {
    if (values == null || values.isEmpty()) {
      return;
    }
    ObjectNode node = JSON.objectNode();
    for (NameValue nv : values) {
      if (nv == null || nv.name() == null) {
        continue;
      }
      Parameter parameter = declared.stream().filter(p -> in.equals(p.getIn()) && nv.name().equals(p.getName()))
          .findFirst().orElse(null);
      node.set(nv.name(), coerce(api, nv.value(), parameter == null ? null : parameter.getSchema()));
    }
    if (node.size() > 0) {
      request.set(key, node);
    }
  }

  /** 문자열 값을 스키마 타입(정수, 숫자, 불리언, 배열)에 맞게 변환한다. 변환할 수 없으면 문자열로 두어 검증이 잡게 한다. */
  private JsonNode coerce(OpenAPI api, String raw, Schema<?> schema) {
    if (raw == null) {
      return JSON.nullNode();
    }
    Schema<?> resolved = schema;
    if (schema != null && schema.get$ref() != null) {
      Schema<?> target = OpenApiSupport.componentSchema(api, OpenApiSupport.simpleName(schema.get$ref()));
      resolved = target == null ? schema : target;
    }
    String type = resolved == null ? null : resolved.getType();
    if (type == null) {
      return JSON.textNode(raw);
    }
    try {
      return switch (type) {
        case "integer" -> JSON.numberNode(Long.parseLong(raw.trim()));
        case "number" -> JSON.numberNode(new BigDecimal(raw.trim()));
        case "boolean" -> "true".equalsIgnoreCase(raw.trim()) || "false".equalsIgnoreCase(raw.trim())
            ? JSON.booleanNode(Boolean.parseBoolean(raw.trim())) : JSON.textNode(raw);
        case "array" -> {
          ArrayNode array = JSON.arrayNode();
          for (String part : raw.split(",")) {
            array.add(coerce(api, part.trim(), resolved.getItems()));
          }
          yield array;
        }
        default -> JSON.textNode(raw);
      };
    } catch (NumberFormatException e) {
      return JSON.textNode(raw);
    }
  }
}
