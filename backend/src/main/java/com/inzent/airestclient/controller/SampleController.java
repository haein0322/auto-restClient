package com.inzent.airestclient.controller;

import com.inzent.airestclient.common.ApiResponse;
import com.inzent.airestclient.dto.SampleDtos.SampleItem;
import com.inzent.airestclient.dto.SampleDtos.SamplePreview;
import com.inzent.airestclient.dto.SampleDtos.SampleSetView;
import com.inzent.airestclient.dto.SampleDtos.UpdateSampleRequest;
import com.inzent.airestclient.service.SampleGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 4단계: 샘플 데이터 생성, 조회, 편집, 개별 재생성 */
@RestController
@RequestMapping("/api/specs/{specId}/samples")
public class SampleController {

  private final SampleGenerationService service;

  public SampleController(SampleGenerationService service) {
    this.service = service;
  }

  /** 모든 엔드포인트의 샘플을 새로 만든다(기존 샘플은 교체). */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SampleSetView> generate(@PathVariable Long specId) {
    return ApiResponse.ok(service.generate(specId));
  }

  /** 1단계 미리보기: 명세만으로 만든 규칙 기반 샘플(저장 안 함, 단계 잠금 없음) */
  @GetMapping("/preview")
  public ApiResponse<SamplePreview> preview(@PathVariable Long specId) {
    return ApiResponse.ok(service.preview(specId));
  }

  @GetMapping
  public ApiResponse<SampleSetView> latest(@PathVariable Long specId) {
    return ApiResponse.ok(service.latest(specId));
  }

  @PutMapping("/{operationId}")
  public ApiResponse<SampleItem> update(@PathVariable Long specId, @PathVariable String operationId,
      @RequestBody UpdateSampleRequest request) {
    return ApiResponse.ok(service.update(specId, operationId, request.request()));
  }

  @PostMapping("/{operationId}/regenerate")
  public ApiResponse<SampleItem> regenerate(@PathVariable Long specId, @PathVariable String operationId) {
    return ApiResponse.ok(service.regenerate(specId, operationId));
  }
}
