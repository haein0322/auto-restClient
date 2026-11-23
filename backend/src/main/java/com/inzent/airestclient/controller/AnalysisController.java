package com.inzent.airestclient.controller;

import com.inzent.airestclient.common.ApiResponse;
import com.inzent.airestclient.common.ApiResponse.ErrorBody;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisView;
import com.inzent.airestclient.service.AnalysisService;
import com.inzent.airestclient.service.SpecService;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 2단계: AI 명세 분석 실행(SSE 진행률 스트리밍)과 결과 조회 */
@RestController
@RequestMapping("/api/specs/{specId}/analysis")
public class AnalysisController {

  private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);
  private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

  private final AnalysisService analysisService;
  private final SpecService specService;
  private final TaskExecutor executor;

  public AnalysisController(AnalysisService analysisService, SpecService specService,
      @Qualifier("analysisExecutor") TaskExecutor executor) {
    this.analysisService = analysisService;
    this.specService = specService;
    this.executor = executor;
  }

  /**
   * 분석을 시작하고 진행 상황을 SSE로 전달한다. 이벤트: progress → (result | error).
   * 클라이언트가 중간에 연결을 끊어도 분석은 끝까지 수행되어 결과가 저장된다.
   */
  @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter analyze(@PathVariable Long specId) {
    specService.getOrThrow(specId); // 존재하지 않으면 SSE 시작 전에 일반 404 JSON으로 응답

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    SafeSender sender = new SafeSender(emitter);
    try {
      executor.execute(() -> run(specId, emitter, sender));
    } catch (RejectedExecutionException e) {
      throw new BusinessException(ErrorCode.AI_FAILED, "분석 요청이 너무 많습니다. 잠시 후 다시 시도하세요.", e);
    }
    return emitter;
  }

  @GetMapping
  public ApiResponse<AnalysisView> latest(@PathVariable Long specId) {
    return ApiResponse.ok(analysisService.latest(specId));
  }

  private void run(Long specId, SseEmitter emitter, SafeSender sender) {
    try {
      AnalysisView view = analysisService.analyze(specId, event -> sender.send("progress", event));
      sender.send("result", view);
    } catch (BusinessException e) {
      log.warn("분석 실패: spec={}, code={}, message={}", specId, e.getErrorCode(), e.getMessage());
      sender.send("error", new ErrorBody(e.getErrorCode().name(), e.getMessage()));
    } catch (RuntimeException e) {
      log.error("분석 중 예상하지 못한 오류: spec={}", specId, e);
      sender.send("error", new ErrorBody(ErrorCode.INTERNAL_ERROR.name(),
          ErrorCode.INTERNAL_ERROR.defaultMessage()));
    } finally {
      emitter.complete();
    }
  }

  /** 클라이언트 연결이 끊긴 뒤의 전송 실패는 무시한다(분석과 저장은 계속 진행). */
  private static final class SafeSender {

    private final SseEmitter emitter;
    private volatile boolean closed;

    SafeSender(SseEmitter emitter) {
      this.emitter = emitter;
    }

    void send(String name, Object data) {
      if (closed) {
        return;
      }
      try {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException e) {
        closed = true;
      }
    }
  }
}
