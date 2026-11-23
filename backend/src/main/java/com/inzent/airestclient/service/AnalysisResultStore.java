package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.SpecAnalysis;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisView;
import com.inzent.airestclient.repository.ApiSpecRepository;
import com.inzent.airestclient.repository.SpecAnalysisRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 결과 저장/조회. AnalysisService는 오래 걸리는 AI 호출을 포함하므로 트랜잭션을 걸지 않고,
 * DB 쓰기만 이 컴포넌트의 짧은 트랜잭션으로 분리한다(AI 호출 동안 DB 커넥션을 점유하지 않기 위함).
 */
@Component
public class AnalysisResultStore {

  private final SpecAnalysisRepository analysisRepository;
  private final ApiSpecRepository specRepository;
  private final ObjectMapper objectMapper;

  public AnalysisResultStore(SpecAnalysisRepository analysisRepository,
      ApiSpecRepository specRepository, ObjectMapper objectMapper) {
    this.analysisRepository = analysisRepository;
    this.specRepository = specRepository;
    this.objectMapper = objectMapper;
  }

  /** 결과 저장과 명세 상태 전이(ANALYZED)를 한 트랜잭션으로 처리한다. */
  @Transactional
  public AnalysisView save(Long specId, AnalysisReport report) {
    ApiSpec spec = specRepository.findById(specId).orElseThrow(
        () -> new BusinessException(ErrorCode.NOT_FOUND, "명세를 찾을 수 없습니다: " + specId));
    SpecAnalysis saved = analysisRepository.save(new SpecAnalysis(specId, toJson(report)));
    spec.advanceTo(SpecStatus.ANALYZED);
    return new AnalysisView(specId, saved.getCreatedAt(), report);
  }

  @Transactional(readOnly = true)
  public AnalysisView latest(Long specId) {
    SpecAnalysis found = analysisRepository.findFirstBySpecIdOrderByIdDesc(specId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
    try {
      return new AnalysisView(specId, found.getCreatedAt(),
          objectMapper.readValue(found.getResultJson(), AnalysisReport.class));
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "저장된 분석 결과를 읽지 못했습니다.", e);
    }
  }

  private String toJson(AnalysisReport report) {
    try {
      return objectMapper.writeValueAsString(report);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "분석 결과를 저장 형식으로 변환하지 못했습니다.", e);
    }
  }
}
