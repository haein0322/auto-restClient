package com.inzent.airestclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.domain.SpecStatus;
import com.inzent.airestclient.domain.TestCaseResult;
import com.inzent.airestclient.domain.TestRun;
import com.inzent.airestclient.dto.TestDtos.DiagnosisView;
import com.inzent.airestclient.dto.TestDtos.TestCaseView;
import com.inzent.airestclient.dto.TestDtos.TestRunSummary;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import com.inzent.airestclient.repository.ApiSpecRepository;
import com.inzent.airestclient.repository.TestCaseResultRepository;
import com.inzent.airestclient.repository.TestRunRepository;
import com.inzent.airestclient.testrun.TestExecutor.CaseOutcome;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 통신 테스트 결과 저장/조회. 네트워크 호출(오래 걸림)과 분리해 DB 쓰기만 짧은 트랜잭션으로 처리한다. */
@Component
public class TestRunStore {

  private final TestRunRepository runRepository;
  private final TestCaseResultRepository caseRepository;
  private final ApiSpecRepository specRepository;
  private final ObjectMapper objectMapper;

  public TestRunStore(TestRunRepository runRepository, TestCaseResultRepository caseRepository,
      ApiSpecRepository specRepository, ObjectMapper objectMapper) {
    this.runRepository = runRepository;
    this.caseRepository = caseRepository;
    this.specRepository = specRepository;
    this.objectMapper = objectMapper;
  }

  /** 실행 결과를 저장하고 명세 상태를 TESTED로 올린다(통과 여부와 무관하게 "테스트를 수행함"을 뜻한다). */
  @Transactional
  public TestRunView save(Long specId, String baseUrl, List<CaseOutcome> outcomes) {
    ApiSpec spec = specRepository.findById(specId).orElseThrow(
        () -> new BusinessException(ErrorCode.NOT_FOUND, "명세를 찾을 수 없습니다: " + specId));
    int passed = (int) outcomes.stream().filter(o -> "PASS".equals(o.outcome())).count();
    TestRun run = runRepository.save(new TestRun(specId, baseUrl, outcomes.size(), passed));
    for (CaseOutcome o : outcomes) {
      caseRepository.save(new TestCaseResult(run.getId(), o.operationId(), o.method(), o.path(), o.statusCode(),
          o.elapsedMs(), o.schemaValid(), o.outcome(), o.request() == null ? null : o.request().toString(),
          o.responseBody(), o.failureReason()));
    }
    spec.advanceTo(SpecStatus.TESTED);
    return view(run, caseRepository.findByRunIdOrderByIdAsc(run.getId()));
  }

  @Transactional(readOnly = true)
  public TestRunView get(Long runId) {
    TestRun run = runRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TEST_RUN_NOT_FOUND));
    return view(run, caseRepository.findByRunIdOrderByIdAsc(runId));
  }

  @Transactional(readOnly = true)
  public List<TestRunSummary> list(Long specId) {
    return runRepository.findBySpecIdOrderByIdDesc(specId).stream().map(run -> {
      List<TestCaseResult> cases = caseRepository.findByRunIdOrderByIdAsc(run.getId());
      return new TestRunSummary(run.getId(), run.getBaseUrl(), run.getCreatedAt(), cases.size(),
          count(cases, "PASS"), count(cases, "FAIL"), count(cases, "SKIPPED"));
    }).toList();
  }

  @Transactional
  public void saveDiagnosis(Long runId, DiagnosisView diagnosis) {
    TestRun run = runRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TEST_RUN_NOT_FOUND));
    try {
      run.saveDiagnosis(objectMapper.writeValueAsString(diagnosis));
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "분석 결과를 저장 형식으로 변환하지 못했습니다.", e);
    }
  }

  private TestRunView view(TestRun run, List<TestCaseResult> cases) {
    List<TestCaseView> views = cases.stream().map(c -> new TestCaseView(c.getOperationId(), c.getMethod(),
        c.getPath(), c.getOutcome(), c.getStatusCode(), c.getElapsedMs(), c.getSchemaValid(),
        readJson(c.getRequestJson()), c.getResponseBody(), c.getFailureReason())).toList();
    return new TestRunView(run.getId(), run.getSpecId(), run.getBaseUrl(), run.getCreatedAt(), views.size(),
        count(cases, "PASS"), count(cases, "FAIL"), count(cases, "SKIPPED"), views, readDiagnosis(run));
  }

  private static int count(List<TestCaseResult> cases, String outcome) {
    return (int) cases.stream().filter(c -> outcome.equals(c.getOutcome())).count();
  }

  private JsonNode readJson(String json) {
    try {
      return json == null ? null : objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private DiagnosisView readDiagnosis(TestRun run) {
    try {
      return run.getDiagnosisJson() == null ? null
          : objectMapper.readValue(run.getDiagnosisJson(), DiagnosisView.class);
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
