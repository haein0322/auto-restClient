package com.inzent.airestclient.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 샘플 요청이 명세를 만족하는지 검증한다: 필수 파라미터 존재, 선언되지 않은 파라미터, 각 값의 스키마, 본문 스키마.
 * AI 결과·사용자 편집값·규칙 기반 결과 모두 이 검증을 통과해야 valid로 표시된다.
 */
public class SampleValidator {

  private static final Set<String> ALLOWED_KEYS = Set.of("pathParams", "queryParams", "headers", "body");

  private final OpenApiSchemaValidator schemaValidator;
  private final DefaultSampleBuilder builder;

  public SampleValidator(OpenApiSchemaValidator schemaValidator, DefaultSampleBuilder builder) {
    this.schemaValidator = schemaValidator;
    this.builder = builder;
  }

  public List<String> validate(String path, String method, Operation operation, PathItem item, JsonNode request) {
    List<String> errors = new ArrayList<>();
    if (request == null || !request.isObject()) {
      return List.of("샘플은 JSON 객체여야 합니다.");
    }
    request.fieldNames().forEachRemaining(key -> {
      if (!ALLOWED_KEYS.contains(key)) {
        errors.add("알 수 없는 최상위 키입니다: " + key + " (pathParams, queryParams, headers, body만 사용할 수 있습니다)");
      }
    });

    List<Parameter> declared = builder.declaredParameters(operation, item);
    validateParams(errors, declared, "path", "pathParams", request.get("pathParams"));
    validateParams(errors, declared, "query", "queryParams", request.get("queryParams"));
    validateParams(errors, declared, "header", "headers", request.get("headers"));
    validateBody(errors, path, method, operation, request.get("body"));
    return errors;
  }

  private void validateParams(List<String> errors, List<Parameter> declared, String in, String key, JsonNode values) {
    Map<String, Parameter> byName = declared.stream().filter(p -> in.equals(p.getIn()))
        .collect(Collectors.toMap(Parameter::getName, p -> p, (a, b) -> a));
    if (values != null && !values.isObject()) {
      errors.add(key + "는 객체여야 합니다.");
      return;
    }

    Set<String> provided = new HashSet<>();
    if (values != null) {
      values.fields().forEachRemaining(entry -> {
        provided.add(entry.getKey());
        Parameter parameter = byName.get(entry.getKey());
        if (parameter == null) {
          errors.add(key + "." + entry.getKey() + ": 명세에 선언되지 않은 파라미터입니다.");
          return;
        }
        if (parameter.getSchema() != null) {
          schemaValidator.validate(SampleSchemaView.plain(parameter.getSchema()), entry.getValue())
              .forEach(message -> errors.add(key + "." + entry.getKey() + ": " + message));
        }
      });
    }
    byName.values().stream()
        .filter(p -> "path".equals(in) || Boolean.TRUE.equals(p.getRequired()))
        .filter(p -> !provided.contains(p.getName()))
        .forEach(p -> errors.add(key + "." + p.getName() + ": 필수 파라미터가 없습니다."));
  }

  private void validateBody(List<String> errors, String path, String method, Operation operation, JsonNode body) {
    MediaType json = builder.jsonBody(operation);
    boolean hasBodyDefinition = json != null;
    boolean provided = body != null && !body.isNull();

    if (!hasBodyDefinition) {
      if (provided) {
        errors.add("body: 이 엔드포인트는 JSON 요청 본문을 받지 않습니다.");
      }
      return;
    }
    if (!provided) {
      boolean required = operation.getRequestBody() != null
          && Boolean.TRUE.equals(operation.getRequestBody().getRequired());
      if (required) {
        errors.add("body: 필수 요청 본문이 없습니다.");
      }
      return;
    }
    schemaValidator.requestSchema(path, method)
        .ifPresent(schema -> schemaValidator.validate(schema, body)
            .forEach(message -> errors.add("body" + message.replaceFirst("^\\$", ""))));
  }
}
