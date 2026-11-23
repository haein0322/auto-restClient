package com.inzent.airestclient.controller;

import com.inzent.airestclient.common.ApiResponse;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.SpecDtos.SampleInfo;
import com.inzent.airestclient.dto.SpecDtos.SpecDetail;
import com.inzent.airestclient.dto.SpecDtos.SpecSummary;
import com.inzent.airestclient.dto.SpecDtos.SpecUrlRequest;
import com.inzent.airestclient.service.SampleSpecProvider;
import com.inzent.airestclient.service.SpecService;
import com.inzent.airestclient.service.UrlSpecFetcher;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 1단계: Swagger 업로드(파일/URL/샘플) 및 명세 조회 */
@RestController
@RequestMapping("/api/specs")
public class SpecController {

  private final SpecService specService;
  private final UrlSpecFetcher urlFetcher;
  private final SampleSpecProvider sampleProvider;

  public SpecController(SpecService specService, UrlSpecFetcher urlFetcher,
      SampleSpecProvider sampleProvider) {
    this.specService = specService;
    this.urlFetcher = urlFetcher;
    this.sampleProvider = sampleProvider;
  }

  @GetMapping
  public ApiResponse<List<SpecSummary>> list() {
    return ApiResponse.ok(specService.list());
  }

  @PostMapping(consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SpecSummary> upload(@RequestParam("file") MultipartFile file) {
    if (file.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일은 업로드할 수 없습니다.");
    }
    try {
      String content = new String(file.getBytes(), StandardCharsets.UTF_8);
      return ApiResponse.ok(specService.register(content, "FILE"));
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "파일을 읽지 못했습니다.", e);
    }
  }

  @PostMapping("/url")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SpecSummary> fromUrl(@Valid @RequestBody SpecUrlRequest request) {
    return ApiResponse.ok(specService.register(urlFetcher.fetch(request.url()), "URL"));
  }

  @GetMapping("/samples")
  public ApiResponse<List<SampleInfo>> samples() {
    return ApiResponse.ok(sampleProvider.list());
  }

  @PostMapping("/samples/{name}")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SpecSummary> loadSample(@PathVariable String name) {
    return ApiResponse.ok(specService.register(sampleProvider.load(name), "SAMPLE"));
  }

  @GetMapping("/{id}")
  public ApiResponse<SpecDetail> detail(@PathVariable Long id) {
    return ApiResponse.ok(specService.detail(id));
  }
}
