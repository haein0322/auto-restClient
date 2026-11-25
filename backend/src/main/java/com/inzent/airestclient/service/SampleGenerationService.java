package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.SampleData;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.dto.SampleDtos.SampleItem;
import com.inzent.airestclient.dto.SampleDtos.SamplePreview;
import com.inzent.airestclient.dto.SampleDtos.SamplePreviewItem;
import com.inzent.airestclient.dto.SampleDtos.SampleSetView;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.sample.DefaultSampleBuilder;
import com.inzent.airestclient.sample.SampleResolver;
import com.inzent.airestclient.sample.SampleResolver.Resolved;
import com.inzent.airestclient.sample.SampleResolver.Target;
import com.inzent.airestclient.sample.SampleValidator;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 4단계: 샘플 데이터 생성·재생성·편집.
 * AI 호출을 포함하므로 트랜잭션을 걸지 않고, 저장만 {@link SampleStore}의 짧은 트랜잭션으로 처리한다.
 */
@Service
public class SampleGenerationService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final SpecService specService;
  private final SwaggerParseService parseService;
  private final SampleResolver resolver;
  private final SampleStore store;
  private final ObjectMapper objectMapper;

  public SampleGenerationService(SpecService specService, SwaggerParseService parseService,
      SampleResolver resolver, SampleStore store, ObjectMapper objectMapper) {
    this.specService = specService;
    this.parseService = parseService;
    this.resolver = resolver;
    this.store = store;
    this.objectMapper = objectMapper;
  }

  /** 명세와 엔드포인트별 대상(Operation)을 한 번에 준비한 컨텍스트 */
  private record Context(OpenAPI api, OpenApiSchemaValidator validator, String title,
      List<EndpointInfo> endpoints, Map<String, Target> targets) {}

  public SampleSetView generate(Long specId) {
    Context ctx = prepare(specId);
    SampleResolver.Result result = resolver.resolve(ctx.api(), ctx.validator(), ctx.title(),
        new ArrayList<>(ctx.targets().values()));
    store.replaceAll(specId, result.samples());
    return view(ctx, specId, store.findAll(specId), result.warnings());
  }

  public SampleItem regenerate(Long specId, String operationId) {
    Context ctx = prepare(specId);
    Target target = target(ctx, operationId);
    SampleResolver.Result result = resolver.resolve(ctx.api(), ctx.validator(), ctx.title(), List.of(target));
    store.upsert(specId, result.samples().get(0));
    return itemOf(ctx, specId, operationId);
  }

  /** 사용자가 편집한 샘플을 검증해 저장한다. 검증 실패도 저장하되 valid=false와 오류를 함께 돌려준다. */
  public SampleItem update(Long specId, String operationId, JsonNode request) {
    if (request == null || request.isNull()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "샘플 내용이 비어 있습니다.");
    }
    Context ctx = prepare(specId);
    Target target = target(ctx, operationId);
    SampleValidator validator = new SampleValidator(ctx.validator(), new DefaultSampleBuilder(ctx.api()));
    List<String> errors = validator.validate(target.path(), target.method(), target.operation(), target.item(), request);
    store.upsert(specId, new Resolved(operationId, request, errors.isEmpty(), errors, "USER"));
    return itemOf(ctx, specId, operationId);
  }

  public SampleSetView latest(Long specId) {
    ApiSpec spec = specService.getOrThrow(specId);
    List<SampleData> rows = store.findAll(specId);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.SAMPLES_NOT_FOUND);
    }
    return view(contextOf(spec, specId), specId, rows, List.of());
  }

  /**
   * 1단계 화면용 미리보기. 단계 잠금과 무관하게 명세만으로 규칙 기반 샘플을 만들어 돌려준다.
   * AI를 호출하지 않고 저장도 하지 않으므로 비용·부작용이 없다.
   */
  public SamplePreview preview(Long specId) {
    Context ctx = contextOf(specService.getOrThrow(specId), specId);
    DefaultSampleBuilder builder = new DefaultSampleBuilder(ctx.api());
    List<SamplePreviewItem> items = ctx.endpoints().stream()
        .filter(ep -> ctx.targets().containsKey(ep.operationId()))
        .map(ep -> {
          Target target = ctx.targets().get(ep.operationId());
          return new SamplePreviewItem(ep.operationId(), builder.build(target.operation(), target.item()));
        })
        .toList();
    return new SamplePreview(specId, items);
  }

  // ---------------- 내부 ----------------

  private Context prepare(Long specId) {
    ApiSpec spec = specService.getOrThrow(specId);
    if (spec.getStatus().ordinal() < SpecStatus.CLIENT_GENERATED.ordinal()) {
      throw new BusinessException(ErrorCode.STEP_NOT_READY, "RestClient 코드 생성을 먼저 완료해야 샘플을 만들 수 있습니다.");
    }
    return contextOf(spec, specId);
  }

  private Context contextOf(ApiSpec spec, Long specId) {
    OpenAPI api = parseService.parseModel(spec.getContent());
    OpenApiSchemaValidator validator;
    try {
      validator = OpenApiSchemaValidator.fromContent(spec.getContent());
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INVALID_SPEC, "명세를 읽지 못했습니다.", e);
    }
    List<EndpointInfo> endpoints = specService.detail(specId).endpoints();
    Map<String, Target> targets = new LinkedHashMap<>();
    for (EndpointInfo ep : endpoints) {
      PathItem item = api.getPaths().get(ep.path());
      Operation operation = item == null ? null : item.readOperationsMap().get(PathItem.HttpMethod.valueOf(ep.method()));
      if (operation != null) {
        targets.put(ep.operationId(), new Target(ep.operationId(), ep.method(), ep.path(), operation, item));
      }
    }
    return new Context(api, validator, spec.getTitle(), endpoints, targets);
  }

  private Target target(Context ctx, String operationId) {
    Target target = ctx.targets().get(operationId);
    if (target == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 엔드포인트입니다: " + operationId);
    }
    return target;
  }

  private SampleItem itemOf(Context ctx, Long specId, String operationId) {
    return view(ctx, specId, store.findAll(specId), List.of()).samples().stream()
        .filter(s -> s.operationId().equals(operationId)).findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.SAMPLES_NOT_FOUND));
  }

  /** 저장된 행을 명세의 엔드포인트 순서대로 화면용 DTO로 조립한다. */
  private SampleSetView view(Context ctx, Long specId, List<SampleData> rows, List<String> warnings) {
    Map<String, SampleData> byOperation = new LinkedHashMap<>();
    rows.forEach(row -> byOperation.put(row.getOperationId(), row));

    List<SampleItem> items = new ArrayList<>();
    for (EndpointInfo ep : ctx.endpoints()) {
      SampleData row = byOperation.get(ep.operationId());
      if (row != null) {
        items.add(new SampleItem(ep.operationId(), ep.method(), ep.path(), readJson(row.getRequestJson()),
            row.isValid(), readErrors(row.getValidationLog()), row.getSource(), row.getUpdatedAt()));
      }
    }
    int valid = (int) items.stream().filter(SampleItem::valid).count();
    return new SampleSetView(specId, resolver.provider(), valid, items.size(), warnings, items);
  }

  private JsonNode readJson(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException | NullPointerException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 샘플을 읽지 못했습니다.", e);
    }
  }

  private List<String> readErrors(String json) {
    try {
      return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }
}
