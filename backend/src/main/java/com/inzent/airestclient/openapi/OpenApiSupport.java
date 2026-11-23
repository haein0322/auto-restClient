package com.inzent.airestclient.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAPI 모델의 $ref 해석·파라미터 병합 등 파서와 코드 생성기가 함께 쓰는 헬퍼 */
public final class OpenApiSupport {

  private OpenApiSupport() {}

  /** "#/components/schemas/Product" → "Product" */
  public static String simpleName(String ref) {
    return ref.substring(ref.lastIndexOf('/') + 1);
  }

  public static Parameter resolveParameter(OpenAPI api, Parameter p) {
    if (p == null || p.get$ref() == null) {
      return p;
    }
    if (api.getComponents() == null || api.getComponents().getParameters() == null) {
      return null;
    }
    return api.getComponents().getParameters().get(simpleName(p.get$ref()));
  }

  public static RequestBody resolveRequestBody(OpenAPI api, RequestBody body) {
    if (body == null || body.get$ref() == null) {
      return body;
    }
    if (api.getComponents() == null || api.getComponents().getRequestBodies() == null) {
      return body;
    }
    RequestBody resolved = api.getComponents().getRequestBodies().get(simpleName(body.get$ref()));
    return resolved != null ? resolved : body;
  }

  public static ApiResponse resolveResponse(OpenAPI api, ApiResponse response) {
    if (response == null || response.get$ref() == null) {
      return response;
    }
    if (api.getComponents() == null || api.getComponents().getResponses() == null) {
      return response;
    }
    ApiResponse resolved = api.getComponents().getResponses().get(simpleName(response.get$ref()));
    return resolved != null ? resolved : response;
  }

  public static Schema<?> componentSchema(OpenAPI api, String name) {
    if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
      return null;
    }
    return api.getComponents().getSchemas().get(name);
  }

  /** 경로 공통 파라미터와 오퍼레이션 파라미터를 합친다(같은 name+in이면 오퍼레이션 정의가 우선). */
  public static List<Parameter> mergedParameters(OpenAPI api, List<Parameter> pathLevel,
      List<Parameter> opLevel) {
    Map<String, Parameter> merged = new LinkedHashMap<>();
    for (List<Parameter> list : Arrays.asList(pathLevel, opLevel)) {
      if (list == null) {
        continue;
      }
      for (Parameter raw : list) {
        Parameter p = resolveParameter(api, raw);
        if (p != null && p.getName() != null) {
          merged.put(p.getIn() + ":" + p.getName(), p);
        }
      }
    }
    return new ArrayList<>(merged.values());
  }

  /** allOf를 재귀적으로 풀어 속성(이름 → 스키마)을 선언 순서대로 병합한다. */
  public static void collectProperties(OpenAPI api, Schema<?> schema, Map<String, Schema<?>> into) {
    collectProperties(api, schema, into, 0);
  }

  private static void collectProperties(OpenAPI api, Schema<?> schema, Map<String, Schema<?>> into, int depth) {
    if (schema == null || depth > 12) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null) {
      collectProperties(api, componentSchema(api, simpleName(ref)), into, depth + 1);
      return;
    }
    if (schema.getAllOf() != null) {
      for (Schema<?> member : schema.getAllOf()) {
        collectProperties(api, member, into, depth + 1);
      }
    }
    if (schema.getProperties() != null) {
      schema.getProperties().forEach((name, propSchema) -> into.put(name, propSchema));
    }
  }
}
