package com.inzent.airestclient.service;

import com.inzent.airestclient.ai.AiClient;
import com.inzent.airestclient.ai.AiClient.DiagnosisInput;
import com.inzent.airestclient.ai.AiClient.FailureInfo;
import com.inzent.airestclient.ai.RuleBasedDiagnosis;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.dto.TestDtos.AiDiagnosisOutput;
import com.inzent.airestclient.dto.TestDtos.CaseDiagnosis;
import com.inzent.airestclient.dto.TestDtos.DiagnosisView;
import com.inzent.airestclient.dto.TestDtos.TestCaseView;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 실패한 통신 테스트 케이스의 AI 원인 분석. AI 응답은 신뢰하지 않고 실패한 케이스에 대해서만 채택하며,
 * 누락되거나 AI 호출이 실패하면 규칙 기반 진단으로 채운다.
 */
@Service
public class DiagnosisService {

  private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);
  private static final int MAX_TEXT = 400;
  private static final int MAX_FAILURES = 30;

  private final TestRunStore store;
  private final AiClient aiClient;

  public DiagnosisService(TestRunStore store, AiClient aiClient) {
    this.store = store;
    this.aiClient = aiClient;
  }

  public DiagnosisView diagnose(Long runId, String title) {
    TestRunView run = store.get(runId);
    List<TestCaseView> failed = run.cases().stream().filter(c -> "FAIL".equals(c.outcome()))
        .limit(MAX_FAILURES).toList();
    if (failed.isEmpty()) {
      return new DiagnosisView(aiClient.provider(), LocalDateTime.now(), "실패한 케이스가 없어 분석할 내용이 없습니다.", List.of());
    }

    List<FailureInfo> failures = failed.stream().map(c -> new FailureInfo(c.operationId(), c.method(), c.path(),
        c.statusCode(), c.failureReason(), c.request() == null ? null : c.request().toString(), c.responseBody())).toList();

    String provider = aiClient.provider();
    String note = "";
    AiDiagnosisOutput output = null;
    try {
      output = aiClient.diagnose(new DiagnosisInput(title, failures));
    } catch (BusinessException e) {
      log.warn("AI 원인 분석 실패: {}", e.getMessage());
      provider = "rule-based";
      note = " (AI 호출에 실패해 규칙 기반 분석으로 대체했습니다: " + e.getMessage() + ")";
    }

    // AI가 반환한 항목 중 실제 실패한 케이스만 채택하고, 누락된 케이스는 규칙 기반 진단으로 채운다.
    Map<String, CaseDiagnosis> byOperation = new LinkedHashMap<>();
    if (output != null && output.cases() != null) {
      for (CaseDiagnosis d : output.cases()) {
        boolean known = d != null && failed.stream().anyMatch(c -> c.operationId().equals(d.operationId()));
        if (known) {
          byOperation.putIfAbsent(d.operationId(), new CaseDiagnosis(d.operationId(),
              truncate(d.likelyCause()), truncate(d.suggestion())));
        }
      }
    }
    for (FailureInfo failure : failures) {
      byOperation.computeIfAbsent(failure.operationId(), id -> RuleBasedDiagnosis.diagnose(failure));
    }

    String summary = output != null && output.summary() != null && !output.summary().isBlank()
        ? truncate(output.summary()) : "실패 " + failed.size() + "건을 규칙 기반으로 분석했습니다.";
    DiagnosisView view = new DiagnosisView(provider, LocalDateTime.now(), summary + note,
        List.copyOf(byOperation.values()));
    store.saveDiagnosis(runId, view);
    return view;
  }

  private static String truncate(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
  }
}
