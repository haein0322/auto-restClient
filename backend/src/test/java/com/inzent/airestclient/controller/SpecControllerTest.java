package com.inzent.airestclient.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SpecControllerTest {

  @Autowired
  private MockMvc mvc;

  private MockMultipartFile file(String content) {
    return new MockMultipartFile("file", "spec.yaml", "application/x-yaml",
        content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void 샘플_목록을_조회한다() throws Exception {
    // 화면에는 샘플 API 하나만 노출한다(나머지 명세는 자동 테스트 전용이라 이름으로만 불러올 수 있다).
    mvc.perform(get("/api/specs/samples"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("sample-api"));
  }

  @Test
  void 쇼핑몰_샘플을_불러와_상세를_조회한다() throws Exception {
    MvcResult created = mvc.perform(post("/api/specs/samples/shop-sample"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.endpointCount").value(12))
        .andExpect(jsonPath("$.data.status").value("PARSED"))
        .andReturn();
    String body = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
    String id = com.jayway.jsonpath.JsonPath.read(body, "$.data.id").toString();

    mvc.perform(get("/api/specs/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.endpoints.length()").value(12))
        .andExpect(jsonPath("$.data.servers[0]").value("http://localhost:8090/mock"))
        .andExpect(jsonPath("$.data.securitySchemes[0].keyName").value("X-API-KEY"))
        // 공개 API는 secured=false, 나머지는 true
        .andExpect(jsonPath("$.data.endpoints[?(@.operationId=='listProducts')].secured").value(false))
        .andExpect(jsonPath("$.data.endpoints[?(@.operationId=='createProduct')].secured").value(true))
        // 경로 레벨 $ref 파라미터가 해석되어야 한다
        .andExpect(jsonPath("$.data.endpoints[?(@.operationId=='getProduct')].parameters[0].name")
            .value("productId"));
  }

  @Test
  void 파일_업로드가_된다() throws Exception {
    String spec = """
        openapi: 3.0.3
        info: { title: 업로드 테스트, version: '1.0' }
        paths:
          /ping:
            get:
              responses:
                '200': { description: ok }
        """;
    mvc.perform(multipart("/api/specs").file(file(spec)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.title").value("업로드 테스트"))
        .andExpect(jsonPath("$.data.sourceType").value("FILE"));
  }

  @Test
  void operationId가_없으면_자동_생성하고_경고한다() throws Exception {
    String spec = """
        openapi: 3.0.3
        info: { title: 자동생성, version: '1.0' }
        paths:
          /items/{id}:
            get:
              parameters:
                - { name: id, in: path, required: true, schema: { type: integer } }
              responses:
                '200': { description: ok }
        """;
    MvcResult created = mvc.perform(multipart("/api/specs").file(file(spec)))
        .andExpect(status().isCreated()).andReturn();
    String id = com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id").toString();

    mvc.perform(get("/api/specs/" + id))
        .andExpect(jsonPath("$.data.endpoints[0].operationId").value("get_items_id"))
        .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("자동 생성")));
  }

  @Test
  void 오류_샘플은_422로_거부되고_저장되지_않는다() throws Exception {
    mvc.perform(post("/api/specs/samples/invalid-sample"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("INVALID_SPEC"));
  }

  @Test
  void 외부_참조는_거부한다() throws Exception {
    String spec = """
        openapi: 3.0.3
        info: { title: 외부참조, version: '1.0' }
        paths:
          /x:
            get:
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        $ref: 'file:///etc/passwd#/foo'
        """;
    mvc.perform(multipart("/api/specs").file(file(spec)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("외부 참조")));
  }

  @Test
  void 형식이_잘못된_파일과_빈_파일은_거부한다() throws Exception {
    mvc.perform(multipart("/api/specs").file(file("::: not [valid yaml")))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(multipart("/api/specs").file(file("")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 존재하지_않는_샘플과_명세는_404() throws Exception {
    mvc.perform(post("/api/specs/samples/nothing")).andExpect(status().isNotFound());
    mvc.perform(get("/api/specs/999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void URL_불러오기는_내부_주소를_차단한다() throws Exception {
    for (String url : new String[] {
        "http://localhost:8090/mock/products", "http://127.0.0.1/x", "http://169.254.169.254/latest",
        "http://10.0.0.5/spec", "file:///etc/passwd", "ftp://example.com/spec"}) {
      mvc.perform(post("/api/specs/url").contentType(MediaType.APPLICATION_JSON)
              .content("{\"url\":\"" + url + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
    mvc.perform(post("/api/specs/url").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 명세_목록은_최신순이다() throws Exception {
    mvc.perform(post("/api/specs/samples/legacy-v2-sample")).andExpect(status().isCreated());
    mvc.perform(get("/api/specs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].title").value("레거시 메모 API (Swagger 2.0)"));
  }
}
