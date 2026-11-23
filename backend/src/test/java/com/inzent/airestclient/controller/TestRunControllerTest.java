package com.inzent.airestclient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inzent.airestclient.dto.TestDtos.TestCaseView;
import com.inzent.airestclient.dto.TestDtos.TestOptions;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import com.inzent.airestclient.service.AnalysisService;
import com.inzent.airestclient.service.TestRunService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 5단계 통신 테스트: 내장 Mock 서버를 실제 HTTP로 호출해 전 과정을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TestRunControllerTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private AnalysisService analysisService;

  @Autowired
  private TestRunService testRunService;

  @LocalServerPort
  private int port;

  private String body(ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String mockUrl() {
    return "http://localhost:" + port + "/mock";
  }

  private long registerSample() throws Exception {
    return ((Number) JsonPath.read(body(mvc.perform(post("/api/specs/samples/shop-sample"))), "$.data.id")).longValue();
  }

  /** 등록 → 분석 → 코드 생성 → 샘플 생성까지 마쳐 5단계가 열린 명세 */
  private long readySpec() throws Exception {
    long id = registerSample();
    analysisService.analyze(id, event -> { });
    mvc.perform(post("/api/specs/" + id + "/client")).andExpect(status().isCreated());
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated());
    return id;
  }

  private TestRunView run(long specId, TestOptions options, List<Integer> percents) {
    return testRunService.execute(testRunService.prepare(specId, options), event -> percents.add(event.percent()));
  }

  private static TestOptions options(String baseUrl, String apiKey, boolean destructive) {
    return new TestOptions(baseUrl, apiKey, destructive, false, 15);
  }

  private static TestCaseView find(TestRunView run, String operationId) {
    return run.cases().stream().filter(c -> c.operationId().equals(operationId)).findFirst().orElseThrow();
  }

  private static List<String> failures(TestRunView run) {
    return run.cases().stream().filter(c -> "FAIL".equals(c.outcome()))
        .map(c -> c.operationId() + "=" + c.failureReason()).toList();
  }

  @Test
  void 샘플_생성_전에는_테스트를_실행할_수_없다() throws Exception {
    long id = registerSample();

    mvc.perform(post("/api/specs/" + id + "/test/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"baseUrl\":\"" + mockUrl() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STEP_NOT_READY"));
  }

  @Test
  void 기본값으로_명세의_서버_주소가_내려온다() throws Exception {
    long id = readySpec();

    mvc.perform(get("/api/specs/" + id + "/test/defaults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.baseUrl").isNotEmpty());
  }

  @Test
  void 허용되지_않은_호스트와_잘못된_주소는_거부된다() throws Exception {
    long id = readySpec();
    String url = "/api/specs/" + id + "/test/runs";

    mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"http://example.org/api\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("외부 서버 호출 확인")));
    mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"ftp://localhost/x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"baseUrl\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 내장_Mock으로_실행하면_위험_호출을_제외하고_모두_통과한다() throws Exception {
    long id = readySpec();
    List<Integer> percents = new ArrayList<>();

    TestRunView run = run(id, options(mockUrl(), "demo-key", false), percents);

    assertThat(run.total()).isEqualTo(12);
    assertThat(failures(run)).isEmpty();
    assertThat(run.skipped()).isGreaterThan(0);
    assertThat(run.passed() + run.skipped()).isEqualTo(12);
    // 위험(삭제) 호출은 허용하지 않으면 건너뛴다
    assertThat(run.cases().stream().filter(c -> "SKIPPED".equals(c.outcome())).map(TestCaseView::method))
        .allMatch("DELETE"::equals);
    // 통과한 케이스는 실제 상태 코드와 스키마 검증 결과가 기록된다
    TestCaseView created = find(run, "createProduct");
    assertThat(created.outcome()).isEqualTo("PASS");
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.schemaValid()).isTrue();
    // 진행률은 단조 증가하며 100으로 끝난다
    assertThat(percents).isSorted();
    assertThat(percents.get(percents.size() - 1)).isEqualTo(100);
  }

  @Test
  void 위험_호출을_허용하면_전부_통과하고_반복_실행해도_충돌하지_않는다() throws Exception {
    long id = readySpec();

    TestRunView first = run(id, options(mockUrl(), "demo-key", true), new ArrayList<>());
    TestRunView second = run(id, options(mockUrl(), "demo-key", true), new ArrayList<>());

    for (TestRunView run : List.of(first, second)) {
      assertThat(run.skipped()).isZero();
      assertThat(failures(run)).isEmpty();
      assertThat(run.passed()).isEqualTo(12);
    }
    // 이메일 고유화 덕분에 두 번째 실행에서도 고객 등록이 409로 거부되지 않는다
    assertThat(find(second, "createCustomer").statusCode()).isEqualTo(201);

    mvc.perform(get("/api/specs/" + id + "/test/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].runId").value(second.runId().intValue()));
    mvc.perform(get("/api/specs/" + id + "/test/runs/" + first.runId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cases.length()").value(12));
  }

  @Test
  void 잘못된_API_키로_호출하면_실패하고_원인_분석이_저장된다() throws Exception {
    long id = readySpec();

    TestRunView run = run(id, options(mockUrl(), "wrong-key", false), new ArrayList<>());

    assertThat(run.failed()).isGreaterThan(0);
    TestCaseView failed = run.cases().stream().filter(c -> "FAIL".equals(c.outcome())).findFirst().orElseThrow();
    assertThat(failed.statusCode()).isEqualTo(401);
    assertThat(failed.failureReason()).isNotBlank();

    mvc.perform(post("/api/specs/" + id + "/test/runs/" + run.runId() + "/diagnosis"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cases.length()").value(run.failed()))
        .andExpect(jsonPath("$.data.cases[0].likelyCause").isNotEmpty())
        .andExpect(jsonPath("$.data.cases[0].suggestion").isNotEmpty());

    // 분석 결과는 실행 이력에 함께 저장된다
    mvc.perform(get("/api/specs/" + id + "/test/runs/" + run.runId()))
        .andExpect(jsonPath("$.data.diagnosis.cases.length()").value(run.failed()));
  }

  @Test
  void 다른_명세의_실행_결과는_조회할_수_없다() throws Exception {
    long id = readySpec();
    long other = readySpec();
    TestRunView run = run(id, options(mockUrl(), "demo-key", false), new ArrayList<>());

    mvc.perform(get("/api/specs/" + other + "/test/runs/" + run.runId()))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/specs/" + other + "/test/runs/" + run.runId() + "/diagnosis"))
        .andExpect(status().isNotFound());
  }
}
