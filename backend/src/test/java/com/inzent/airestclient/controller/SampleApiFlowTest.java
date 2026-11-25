package com.inzent.airestclient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inzent.airestclient.dto.TestDtos.TestOptions;
import com.inzent.airestclient.dto.TestDtos.TestRunView;
import com.inzent.airestclient.service.AnalysisService;
import com.inzent.airestclient.service.TestRunService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 화면에 노출되는 "샘플 API"(엔드포인트 3개): 목록, 1단계 미리보기 샘플, 내장 Mock 연동 통신 테스트 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SampleApiFlowTest {

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

  private long register() throws Exception {
    return ((Number) JsonPath.read(body(mvc.perform(post("/api/specs/samples/sample-api"))), "$.data.id")).longValue();
  }

  @Test
  void 샘플_목록에는_샘플_API_하나만_보인다() throws Exception {
    mvc.perform(get("/api/specs/samples"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("sample-api"))
        .andExpect(jsonPath("$.data[0].title").value("샘플 API"));
  }

  @Test
  void 샘플_API는_엔드포인트_3개로_등록된다() throws Exception {
    mvc.perform(post("/api/specs/samples/sample-api"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.title").value("샘플 API"))
        .andExpect(jsonPath("$.data.endpointCount").value(3));
  }

  @Test
  void 단계_잠금과_무관하게_엔드포인트별_미리보기_샘플을_받는다() throws Exception {
    long id = register();

    mvc.perform(get("/api/specs/" + id + "/samples/preview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.samples.length()").value(3))
        // 본문이 있는 등록 API는 필수 필드가 채워져 있어야 한다
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createItem')].request.body.name").isNotEmpty())
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createItem')].request.body.price").isNotEmpty())
        // 경로 파라미터가 있는 조회 API는 pathParams가 채워져 있어야 한다
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='getItem')].request.pathParams.itemId").isNotEmpty());

    // 미리보기는 저장하지 않으므로 4단계 샘플은 여전히 없다
    mvc.perform(get("/api/specs/" + id + "/samples")).andExpect(status().isNotFound());
  }

  @Test
  void 존재하지_않는_명세의_미리보기는_404이다() throws Exception {
    mvc.perform(get("/api/specs/999999/samples/preview")).andExpect(status().isNotFound());
  }

  @Test
  void 내장_Mock으로_3개_엔드포인트가_모두_통과한다() throws Exception {
    long id = register();
    analysisService.analyze(id, event -> { });
    mvc.perform(post("/api/specs/" + id + "/client")).andExpect(status().isCreated());
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated());

    TestRunView run = testRunService.execute(
        testRunService.prepare(id, new TestOptions("http://localhost:" + port + "/mock", "demo-key", false, false, 15)),
        event -> { });

    assertThat(run.total()).isEqualTo(3);
    assertThat(run.failed()).as("실패: %s", run.cases()).isZero();
    assertThat(run.passed()).isEqualTo(3);
  }

  @Test
  void 잘못된_키로_호출하면_Mock은_401을_돌려준다() throws Exception {
    mvc.perform(get("/mock/items")).andExpect(status().isUnauthorized());
    mvc.perform(get("/mock/items").header("X-API-KEY", "demo-key").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").isNotEmpty());
    mvc.perform(get("/mock/items/999999").header("X-API-KEY", "demo-key")).andExpect(status().isNotFound());
  }
}
