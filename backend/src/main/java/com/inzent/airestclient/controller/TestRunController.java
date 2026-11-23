package com.inzent.airestclient.controller;

import com.inzent.airestclient.common.ApiResponse;
import com.inzent.airestclient.common.ApiResponse.ErrorBody;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.TestDtos.DiagnosisView;
import com.inzent.airestclient.dto.TestDtos.TestDefaults;
import com.inzent.airestclient.dto.TestDtos.TestOptions;
import com.inzent.airestclient.dto.TestDtos.TestRunSummary;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import com.inzent.airestclient.service.DiagnosisService;
import com.inzent.airestclient.service.SpecService;
import com.inzent.airestclient.service.TestRunService;
import com.inzent.airestclient.service.TestRunService.Prepared;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 5단계: 통신 테스트 실행(SSE 진행률 스트리밍), 실행 이력 조회, 실패 원인 AI 분석 */
@RestController
@RequestMapping("/api/specs/{specId}/test")
public class TestRunController {

  private static final Logger log = LoggerFactory.getLogger(TestRunController.class);
  private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

  private final TestRunService testRunService;
  private final DiagnosisService diagnosisService;
  private final SpecService specService;
  private final TaskExecutor executor;

  public TestRunController(TestRunService testRunService, DiagnosisService diagnosisService,
      SpecService specService, @Qualifier("analysisExecutor") TaskExecutor executor) {
    this.testRunService = testRunService;
    this.diagnosisService = diagnosisService;
    this.specService = specService;
    this.executor = executor;
  }

  @GetMapping("/defaults")
  public ApiResponse<TestDefaults> defaults(@PathVariable Long specId) {
    return ApiResponse.ok(testRunService.defaults(specId));
  }

  /**
   * 통신 테스트를 시작하고 진행 상황을 SSE로 전달한다. 이벤트: progress → (result | error).
   * 입력 검증 오류(주소, 허용 호스트, 단계 미완료)는 스트림 시작 전에 일반 JSON으로 응답한다.
   * 클라이언트가 연결을 끊어도 실행은 끝까지 진행되어 결과가 저장된다.
   */
  @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter run(@PathVariable Long specId, @RequestBody TestOptions options) {
    Prepared prepared = testRunService.prepare(specId, options);

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    SafeSender sender = new SafeSender(emitter);
    try {
      executor.execute(() -> execute(prepared, emitter, sender));
    } catch (RejectedExecutionException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "테스트 요청이 너무 많습니다. 잠시 후 다시 시도하세요.", e);
    }
    return emitter;
  }

  @GetMapping("/runs")
  public ApiResponse<List<TestRunSummary>> list(@PathVariable Long specId) {
    return ApiResponse.ok(testRunService.list(specId));
  }

  @GetMapping("/runs/{runId}")
  public ApiResponse<TestRunView> get(@PathVariable Long specId, @PathVariable Long runId) {
    return ApiResponse.ok(requireOwned(specId, runId));
  }

  /** 실패한 케이스의 원인을 AI(키가 없으면 규칙 기반)로 분석하고 결과를 저장한다. */
  @PostMapping("/runs/{runId}/diagnosis")
  public ApiResponse<DiagnosisView> diagnose(@PathVariable Long specId, @PathVariable Long runId) {
    requireOwned(specId, runId);
    String title = specService.getOrThrow(specId).getTitle();
    return ApiResponse.ok(diagnosisService.diagnose(runId, title));
  }

  /** 다른 명세의 실행 이력을 경로만 바꿔 읽지 못하도록 소유 명세를 확인한다. */
  private TestRunView requireOwned(Long specId, Long runId) {
    TestRunView run = testRunService.get(runId);
    if (!specId.equals(run.specId())) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "테스트 실행 결과를 찾을 수 없습니다.");
    }
    return run;
  }

  private void execute(Prepared prepared, SseEmitter emitter, SafeSender sender) {
    try {
      TestRunView view = testRunService.execute(prepared, event -> sender.send("progress", event));
      sender.send("result", view);
    } catch (BusinessException e) {
      log.warn("통신 테스트 실패: spec={}, code={}, message={}", prepared.specId(), e.getErrorCode(), e.getMessage());
      sender.send("error", new ErrorBody(e.getErrorCode().name(), e.getMessage()));
    } catch (RuntimeException e) {
      log.error("통신 테스트 중 예상하지 못한 오류: spec={}", prepared.specId(), e);
      sender.send("error", new ErrorBody(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    } finally {
      emitter.complete();
    }
  }

  /** 클라이언트 연결이 끊긴 뒤의 전송 실패는 무시한다(실행과 저장은 계속 진행). */
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
