package com.inzent.airestclient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inzent.airestclient.service.AnalysisService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SampleControllerTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private AnalysisService analysisService;

  private String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private long registerSample() throws Exception {
    return ((Number) JsonPath.read(body(mvc.perform(post("/api/specs/samples/shop-sample"))), "$.data.id")).longValue();
  }

  /** 등록 → 분석 → 코드 생성까지 마쳐 4단계가 열린 명세 */
  private long readySpec() throws Exception {
    long id = registerSample();
    analysisService.analyze(id, event -> { });
    mvc.perform(post("/api/specs/" + id + "/client")).andExpect(status().isCreated());
    return id;
  }

  private String specStatus(long id) throws Exception {
    return JsonPath.read(body(mvc.perform(get("/api/specs/" + id))), "$.data.summary.status");
  }

  @Test
  void 코드_생성_전에는_샘플을_만들_수_없다() throws Exception {
    long id = registerSample();

    mvc.perform(post("/api/specs/" + id + "/samples"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STEP_NOT_READY"));
    mvc.perform(get("/api/specs/" + id + "/samples"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SAMPLES_NOT_FOUND"));
  }

  @Test
  void 생성하면_모든_엔드포인트에_유효한_샘플이_저장되고_상태가_전이된다() throws Exception {
    long id = readySpec();
    assertThat(specStatus(id)).isEqualTo("CLIENT_GENERATED");

    mvc.perform(post("/api/specs/" + id + "/samples"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.provider").value("fake"))
        .andExpect(jsonPath("$.data.total").value(12))
        .andExpect(jsonPath("$.data.validCount").value(12))
        .andExpect(jsonPath("$.data.samples.length()").value(12))
        .andExpect(jsonPath("$.data.samples[0].operationId").value("listProducts"))
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createProduct')].source").value("RULE"))
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createProduct')].request.body.category").value("ELECTRONICS"));

    assertThat(specStatus(id)).isEqualTo("SAMPLES_READY");

    // 다시 조회해도 동일하고, 재생성해도 12건이 유지된다(교체)
    mvc.perform(get("/api/specs/" + id + "/samples"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(12));
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.total").value(12));
    mvc.perform(get("/api/specs/" + id + "/samples"))
        .andExpect(jsonPath("$.data.samples.length()").value(12));
  }

  @Test
  void 사용자가_편집한_샘플은_검증되어_저장된다() throws Exception {
    long id = readySpec();
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated());
    String url = "/api/specs/" + id + "/samples/createProduct";

    // 유효한 편집 → USER 출처, valid
    mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""
            {"request":{"body":{"name":"무선 키보드","price":45000,"category":"ELECTRONICS","stock":3}}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valid").value(true))
        .andExpect(jsonPath("$.data.source").value("USER"))
        .andExpect(jsonPath("$.data.request.body.name").value("무선 키보드"));

    // 위반 편집 → 저장은 되지만 valid=false + 오류 내용
    mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""
            {"request":{"body":{"name":"x","price":-10,"category":"BOOKS"}}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.valid").value(false))
        .andExpect(jsonPath("$.data.errors[0]").value(org.hamcrest.Matchers.startsWith("body.price")));

    mvc.perform(get("/api/specs/" + id + "/samples"))
        .andExpect(jsonPath("$.data.validCount").value(11))
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createProduct')].valid").value(false));
  }

  @Test
  void 개별_재생성은_해당_엔드포인트만_초기화한다() throws Exception {
    long id = readySpec();
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated());
    mvc.perform(put("/api/specs/" + id + "/samples/createProduct").contentType(MediaType.APPLICATION_JSON)
        .content("{\"request\":{\"body\":{\"name\":\"편집\",\"price\":1,\"category\":\"BOOKS\"}}}"));
    mvc.perform(put("/api/specs/" + id + "/samples/createCustomer").contentType(MediaType.APPLICATION_JSON)
        .content("{\"request\":{\"body\":{\"name\":\"편집\",\"email\":\"edited@example.com\"}}}"));

    mvc.perform(post("/api/specs/" + id + "/samples/createProduct/regenerate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source").value("RULE"))
        .andExpect(jsonPath("$.data.valid").value(true))
        .andExpect(jsonPath("$.data.request.body.category").value("ELECTRONICS"));

    // 다른 엔드포인트의 편집 내용은 그대로 유지된다
    mvc.perform(get("/api/specs/" + id + "/samples"))
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createCustomer')].source").value("USER"))
        .andExpect(jsonPath("$.data.samples[?(@.operationId=='createCustomer')].request.body.email").value("edited@example.com"));
  }

  @Test
  void 잘못된_요청은_거부한다() throws Exception {
    long id = readySpec();
    mvc.perform(post("/api/specs/" + id + "/samples")).andExpect(status().isCreated());

    mvc.perform(put("/api/specs/" + id + "/samples/nothing").contentType(MediaType.APPLICATION_JSON)
            .content("{\"request\":{}}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/specs/" + id + "/samples/nothing/regenerate")).andExpect(status().isNotFound());
    mvc.perform(put("/api/specs/" + id + "/samples/createProduct").contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    mvc.perform(post("/api/specs/999999/samples")).andExpect(status().isNotFound());
  }
}
