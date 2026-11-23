package com.inzent.airestclient.service;

import com.inzent.airestclient.ai.AiClient;
import com.inzent.airestclient.ai.AiClient.SpecAnalysisInput;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisView;
import com.inzent.airestclient.dto.AnalysisDtos.ProgressEvent;
import com.inzent.airestclient.dto.SpecDtos.SpecDetail;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 2단계: AI 명세 분석 오케스트레이션.
 * 명세 로드 → AI 호출 → 서버 검증/보정 → 저장 순으로 진행하며 단계마다 진행 상황을 알린다.
 * 트랜잭션은 걸지 않는다(AI 호출이 길어 DB 커넥션을 점유하지 않도록 저장만 {@link AnalysisResultStore}가 처리).
 */
@Service
public class AnalysisService {

  private final SpecService specService;
  private final AiClient aiClient;
  private final AnalysisNormalizer normalizer;
  private final AnalysisResultStore store;
  private final int maxEndpoints;

  public AnalysisService(SpecService specService, AiClient aiClient, AnalysisNormalizer normalizer,
      AnalysisResultStore store, @Value("${app.ai.max-endpoints:200}") int maxEndpoints) {
    this.specService = specService;
    this.aiClient = aiClient;
    this.normalizer = normalizer;
    this.store = store;
    this.maxEndpoints = maxEndpoints;
  }

  public AnalysisView analyze(Long specId, Consumer<ProgressEvent> progress) {
    progress.accept(new ProgressEvent("PREPARE", "명세를 불러오는 중...", 10));
    SpecDetail detail = specService.detail(specId);
    if (detail.endpoints().size() > maxEndpoints) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST,
          "엔드포인트가 너무 많습니다(%d개, 최대 %d개). 명세를 나누어 등록해주세요."
              .formatted(detail.endpoints().size(), maxEndpoints));
    }

    progress.accept(new ProgressEvent("AI_REQUEST",
        "AI가 명세를 분석하는 중... (%s)".formatted(aiClient.provider()), 30));
    AiAnalysisOutput raw = aiClient.analyze(new SpecAnalysisInput(detail.summary().title(),
        detail.description(), detail.servers(), detail.securitySchemes(), detail.endpoints()));

    progress.accept(new ProgressEvent("VALIDATE", "분석 결과를 검증하는 중...", 75));
    AnalysisReport report = normalizer.normalize(raw, detail.endpoints(), detail.securitySchemes(),
        aiClient.provider(), aiClient.model());

    progress.accept(new ProgressEvent("SAVE", "결과를 저장하는 중...", 90));
    AnalysisView view = store.save(specId, report);

    progress.accept(new ProgressEvent("DONE", "분석이 완료되었습니다.", 100));
    return view;
  }

  public AnalysisView latest(Long specId) {
    specService.getOrThrow(specId);
    return store.latest(specId);
  }
}
