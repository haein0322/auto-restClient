package com.inzent.airestclient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.inzent.airestclient.service.AnalysisService;

@SpringBootTest
@AutoConfigureMockMvc
class ClientControllerTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private AnalysisService analysisService;

  private long registerSample() throws Exception {
    String body = mvc.perform(post("/api/specs/samples/shop-sample")).andReturn()
        .getResponse().getContentAsString(StandardCharsets.UTF_8);
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private long registerAnalyzedSample() throws Exception {
    long id = registerSample();
    analysisService.analyze(id, event -> { });
    return id;
  }

  private String specStatus(long id) throws Exception {
    String body = mvc.perform(get("/api/specs/" + id)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    return JsonPath.read(body, "$.data.summary.status");
  }

  @Test
  void 분석_전에는_생성할_수_없다() throws Exception {
    long id = registerSample();

    mvc.perform(post("/api/specs/" + id + "/client"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("STEP_NOT_READY"));
    mvc.perform(get("/api/specs/" + id + "/client"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
  }

  @Test
  void 생성하면_컴파일_결과가_저장되고_상태가_전이된다() throws Exception {
    long id = registerAnalyzedSample();
    assertThat(specStatus(id)).isEqualTo("ANALYZED");

    mvc.perform(post("/api/specs/" + id + "/client"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.compiled").value(true))
        .andExpect(jsonPath("$.data.diagnostics.length()").value(0))
        .andExpect(jsonPath("$.data.basePackage").value("com.generated.client"))
        .andExpect(jsonPath("$.data.files[?(@.path=='src/main/java/com/generated/client/ApiClient.java')]").exists())
        .andExpect(jsonPath("$.data.files[?(@.path=='README.md')]").exists());

    assertThat(specStatus(id)).isEqualTo("CLIENT_GENERATED");

    // 저장된 결과를 다시 조회할 수 있고, 분석 단계에서 정한 메서드명이 사용된다
    String body = mvc.perform(get("/api/specs/" + id + "/client")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    List<String> clientSource = JsonPath.read(body,
        "$.data.files[?(@.path=='src/main/java/com/generated/client/ApiClient.java')].content");
    assertThat(clientSource.get(0)).contains("public ProductPage listProducts(").contains("public Order cancelOrder(");
  }

  @Test
  void 패키지명을_지정할_수_있다() throws Exception {
    long id = registerAnalyzedSample();

    mvc.perform(post("/api/specs/" + id + "/client").contentType(MediaType.APPLICATION_JSON)
            .content("{\"basePackage\":\"com.acme.shop\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.basePackage").value("com.acme.shop"))
        .andExpect(jsonPath("$.data.files[?(@.path=='src/main/java/com/acme/shop/ApiClient.java')]").exists());
  }

  @Test
  void 잘못된_패키지명은_거부한다() throws Exception {
    long id = registerAnalyzedSample();

    for (String bad : new String[] {"../evil", "Com.Example", "com.new.x", "com..x", "1com.x", "com/x", "a".repeat(120)}) {
      mvc.perform(post("/api/specs/" + id + "/client").contentType(MediaType.APPLICATION_JSON)
              .content("{\"basePackage\":\"" + bad + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
  }

  @Test
  void ZIP으로_내려받을_수_있다() throws Exception {
    long id = registerAnalyzedSample();
    mvc.perform(post("/api/specs/" + id + "/client")).andExpect(status().isCreated());

    byte[] zip = mvc.perform(get("/api/specs/" + id + "/client/download"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Content-Disposition", org.hamcrest.Matchers.containsString("rest-client-" + id + ".zip")))
        .andReturn().getResponse().getContentAsByteArray();

    List<String> entries = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.add(entry.getName());
        assertThat(entry.getName()).doesNotContain("..").doesNotStartWith("/");
      }
    }
    assertThat(entries).contains("src/main/java/com/generated/client/ApiClient.java",
        "src/main/java/com/generated/client/model/Product.java", "README.md");
  }

  @Test
  void 생성_전_다운로드는_404() throws Exception {
    long id = registerAnalyzedSample();
    mvc.perform(get("/api/specs/" + id + "/client/download")).andExpect(status().isNotFound());
  }

  @Test
  void 존재하지_않는_명세는_404() throws Exception {
    mvc.perform(post("/api/specs/999999/client")).andExpect(status().isNotFound());
  }
}
