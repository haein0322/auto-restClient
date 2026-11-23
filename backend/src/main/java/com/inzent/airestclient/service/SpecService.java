package com.inzent.airestclient.service;

import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.dto.SpecDtos.SpecDetail;
import com.inzent.airestclient.dto.SpecDtos.SpecSummary;
import com.inzent.airestclient.repository.ApiSpecRepository;
import com.inzent.airestclient.service.SwaggerParseService.ParsedSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 명세 등록·조회 유스케이스 */
@Service
@Transactional(readOnly = true)
public class SpecService {

  private final ApiSpecRepository repository;
  private final SwaggerParseService parseService;

  public SpecService(ApiSpecRepository repository, SwaggerParseService parseService) {
    this.repository = repository;
    this.parseService = parseService;
  }

  /** 파싱에 성공한 명세만 저장한다(실패 시 예외로 롤백되어 아무것도 남지 않는다). */
  @Transactional
  public SpecSummary register(String content, String sourceType) {
    ParsedSpec parsed = parseService.parse(content);
    ApiSpec saved = repository.save(new ApiSpec(parsed.title(), parsed.version(), sourceType,
        content, sha256(content), parsed.endpoints().size(), parsed.schemaCount()));
    return toSummary(saved);
  }

  public List<SpecSummary> list() {
    return repository.findAllByOrderByIdDesc().stream().map(this::toSummary).toList();
  }

  public SpecDetail detail(Long id) {
    ApiSpec spec = getOrThrow(id);
    ParsedSpec parsed = parseService.parse(spec.getContent());
    return new SpecDetail(toSummary(spec), parsed.description(), parsed.servers(),
        parsed.securitySchemes(), parsed.endpoints(), parsed.warnings());
  }

  public ApiSpec getOrThrow(Long id) {
    return repository.findById(id).orElseThrow(
        () -> new BusinessException(ErrorCode.NOT_FOUND, "명세를 찾을 수 없습니다: " + id));
  }

  private SpecSummary toSummary(ApiSpec spec) {
    return new SpecSummary(spec.getId(), spec.getTitle(), spec.getVersion(), spec.getSourceType(),
        spec.getEndpointCount(), spec.getSchemaCount(), spec.getStatus(), spec.getCreatedAt());
  }

  private String sha256(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }
}
