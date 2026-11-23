package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.SampleData;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.repository.ApiSpecRepository;
import com.inzent.airestclient.repository.SampleDataRepository;
import com.inzent.airestclient.sample.SampleResolver.Resolved;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 샘플 저장/조회. AI 호출 등 오래 걸리는 작업과 분리해 DB 쓰기만 짧은 트랜잭션으로 처리한다. */
@Component
public class SampleStore {

  private final SampleDataRepository sampleRepository;
  private final ApiSpecRepository specRepository;
  private final ObjectMapper objectMapper;

  public SampleStore(SampleDataRepository sampleRepository, ApiSpecRepository specRepository,
      ObjectMapper objectMapper) {
    this.sampleRepository = sampleRepository;
    this.specRepository = specRepository;
    this.objectMapper = objectMapper;
  }

  /** 기존 샘플을 모두 교체한다. 유효한 샘플이 하나라도 있으면 명세 상태를 SAMPLES_READY로 올려 다음 단계를 연다. */
  @Transactional
  public void replaceAll(Long specId, List<Resolved> samples) {
    // 벌크 삭제는 영속성 컨텍스트를 비우므로(clearAutomatically), 명세는 삭제 이후에 읽어야 상태 변경이 반영된다.
    sampleRepository.deleteAllBySpecId(specId);
    ApiSpec spec = findSpec(specId);
    samples.forEach(sample -> sampleRepository.save(toEntity(specId, sample)));
    advanceIfAnyValid(spec, samples);
  }

  /** 한 엔드포인트의 샘플을 추가하거나 갱신한다(재생성, 사용자 편집). */
  @Transactional
  public void upsert(Long specId, Resolved sample) {
    ApiSpec spec = findSpec(specId);
    sampleRepository.findBySpecIdAndOperationId(specId, sample.operationId()).ifPresentOrElse(
        existing -> existing.update(json(sample.request()), sample.valid(), json(sample.errors()), sample.source()),
        () -> sampleRepository.save(toEntity(specId, sample)));
    advanceIfAnyValid(spec, List.of(sample));
  }

  @Transactional(readOnly = true)
  public List<SampleData> findAll(Long specId) {
    return sampleRepository.findBySpecId(specId);
  }

  private void advanceIfAnyValid(ApiSpec spec, List<Resolved> samples) {
    if (samples.stream().anyMatch(Resolved::valid)) {
      spec.advanceTo(SpecStatus.SAMPLES_READY);
    }
  }

  private ApiSpec findSpec(Long specId) {
    return specRepository.findById(specId).orElseThrow(
        () -> new BusinessException(ErrorCode.NOT_FOUND, "명세를 찾을 수 없습니다: " + specId));
  }

  private SampleData toEntity(Long specId, Resolved sample) {
    return new SampleData(specId, sample.operationId(), json(sample.request()), sample.valid(),
        json(sample.errors()), sample.source());
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "샘플을 저장 형식으로 변환하지 못했습니다.", e);
    }
  }
}
