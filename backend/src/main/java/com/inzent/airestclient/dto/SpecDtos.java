package com.inzent.airestclient.dto;

import com.inzent.airestclient.domain.SpecStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;

/** 명세 업로드/조회 API의 요청·응답 DTO 모음 */
public final class SpecDtos {

  private SpecDtos() {}

  public record SpecUrlRequest(@NotBlank String url) {}

  public record ParamInfo(String name, String in, boolean required, String type) {}

  public record EndpointInfo(
      String operationId,
      String method,
      String path,
      String summary,
      String tag,
      boolean secured,
      List<ParamInfo> parameters,
      boolean hasRequestBody,
      boolean requestBodyRequired,
      List<String> responseCodes) {}

  public record SecuritySchemeInfo(String name, String type, String location, String keyName) {}

  public record SpecSummary(
      Long id,
      String title,
      String version,
      String sourceType,
      int endpointCount,
      int schemaCount,
      SpecStatus status,
      LocalDateTime createdAt) {}

  public record SpecDetail(
      SpecSummary summary,
      String description,
      List<String> servers,
      List<SecuritySchemeInfo> securitySchemes,
      List<EndpointInfo> endpoints,
      List<String> warnings) {}

  public record SampleInfo(String name, String title, String description) {}
}
