package com.inzent.airestclient.controller;

import com.inzent.airestclient.common.ApiResponse;
import com.inzent.airestclient.dto.ClientDtos.ClientView;
import com.inzent.airestclient.dto.ClientDtos.GenerateClientRequest;
import com.inzent.airestclient.service.ClientGenerationService;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 3단계: RestClient 코드 생성, 조회, ZIP 다운로드 */
@RestController
@RequestMapping("/api/specs/{specId}/client")
public class ClientController {

  private final ClientGenerationService service;

  public ClientController(ClientGenerationService service) {
    this.service = service;
  }

  /** 요청 본문(패키지명)은 선택 사항이다. 생략하면 서버 기본 패키지를 사용한다. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ClientView> generate(@PathVariable Long specId,
      @RequestBody(required = false) GenerateClientRequest request) {
    return ApiResponse.ok(service.generate(specId, request == null ? null : request.basePackage()));
  }

  @GetMapping
  public ApiResponse<ClientView> latest(@PathVariable Long specId) {
    return ApiResponse.ok(service.latest(specId));
  }

  @GetMapping("/download")
  public ResponseEntity<byte[]> download(@PathVariable Long specId) {
    byte[] zip = service.zip(specId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("rest-client-" + specId + ".zip").build().toString())
        .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
        .body(zip);
  }
}
