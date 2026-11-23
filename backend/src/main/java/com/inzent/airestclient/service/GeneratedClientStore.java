package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.GeneratedClient;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.dto.ClientDtos.ClientPayload;
import com.inzent.airestclient.dto.ClientDtos.ClientView;
import com.inzent.airestclient.repository.ApiSpecRepository;
import com.inzent.airestclient.repository.GeneratedClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 생성 결과 저장/조회. 컴파일/생성 같은 무거운 작업과 분리해 DB 쓰기만 짧은 트랜잭션으로 처리한다. */
@Component
public class GeneratedClientStore {

  private final GeneratedClientRepository clientRepository;
  private final ApiSpecRepository specRepository;
  private final ObjectMapper objectMapper;

  public GeneratedClientStore(GeneratedClientRepository clientRepository,
      ApiSpecRepository specRepository, ObjectMapper objectMapper) {
    this.clientRepository = clientRepository;
    this.specRepository = specRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * 결과를 저장한다. 컴파일에 성공한 경우에만 명세 상태를 CLIENT_GENERATED로 올려 다음 단계를 연다
   * (컴파일되지 않는 코드로 통신 테스트가 진행되지 않도록).
   */
  @Transactional
  public ClientView save(Long specId, ClientPayload payload, boolean compiled) {
    ApiSpec spec = specRepository.findById(specId).orElseThrow(
        () -> new BusinessException(ErrorCode.NOT_FOUND, "명세를 찾을 수 없습니다: " + specId));
    GeneratedClient saved = clientRepository.save(new GeneratedClient(specId, toJson(payload), compiled,
        payload.diagnostics().isEmpty() ? null : String.join("\n", payload.diagnostics())));
    if (compiled) {
      spec.advanceTo(SpecStatus.CLIENT_GENERATED);
    }
    return toView(saved, payload);
  }

  @Transactional(readOnly = true)
  public ClientView latest(Long specId) {
    GeneratedClient found = clientRepository.findFirstBySpecIdOrderByIdDesc(specId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    try {
      return toView(found, objectMapper.readValue(found.getFilesJson(), ClientPayload.class));
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 생성 결과를 읽지 못했습니다.", e);
    }
  }

  /** 통신 테스트가 소스와 메서드 메타데이터를 읽을 때 사용한다(컴파일 성공 여부는 호출 측이 다시 컴파일하며 확인한다). */
  @Transactional(readOnly = true)
  public ClientPayload latestPayload(Long specId) {
    GeneratedClient found = clientRepository.findFirstBySpecIdOrderByIdDesc(specId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    try {
      return objectMapper.readValue(found.getFilesJson(), ClientPayload.class);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 생성 결과를 읽지 못했습니다.", e);
    }
  }

  private ClientView toView(GeneratedClient entity, ClientPayload payload) {
    return new ClientView(entity.getSpecId(), entity.getCreatedAt(), entity.isCompiled(),
        payload.basePackage(), payload.diagnostics(), payload.warnings(), payload.files());
  }

  private String toJson(ClientPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "생성 결과를 저장 형식으로 변환하지 못했습니다.", e);
    }
  }
}
