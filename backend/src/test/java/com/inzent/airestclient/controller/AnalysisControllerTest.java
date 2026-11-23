package com.inzent.airestclient.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 HTTP로 SSE 스트리밍을 포함한 분석 흐름 전체(등록 → 분석 → 조회 → 상태 전이)를 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @LocalServerPort
  private int port;

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private JsonNode send(String method, String path) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(20));
    HttpResponse<String> response = http.send(
        method.equals("POST") ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build(),
        HttpResponse.BodyHandlers.ofString());
    JsonNode body = MAPPER.readTree(response.body());
    ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("_status", response.statusCode());
    return body;
  }

  private long registerSample(String name) throws Exception {
    return send("POST", "/api/specs/samples/" + name).get("data").get("id").asLong();
  }

  /** SSE 응답 본문을 (이벤트명, JSON) 목록으로 파싱한다. */
  private record SseEvent(String name, JsonNode data) {}

  private List<SseEvent> parseSse(String body) throws Exception {
    List<SseEvent> events = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String name = null;
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          data.append(line.substring(5).trim());
        }
      }
      if (name != null) {
        events.add(new SseEvent(name, MAPPER.readTree(data.toString())));
      }
    }
    return events;
  }

  @Test
  void 분석_전에는_결과_조회가_404이고_분석_후에는_조회되며_상태가_ANALYZED가_된다() throws Exception {
    long id = registerSample("shop-sample");

    JsonNode before = send("GET", "/api/specs/" + id + "/analysis");
    assertThat(before.get("_status").asInt()).isEqualTo(404);
    assertThat(before.get("error").get("code").asText()).isEqualTo("ANALYSIS_NOT_FOUND");

    HttpResponse<String> sse = http.send(
        HttpRequest.newBuilder(URI.create(url("/api/specs/" + id + "/analysis")))
            .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(sse.statusCode()).isEqualTo(200);
    assertThat(sse.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

    List<SseEvent> events = parseSse(sse.body());
    List<SseEvent> progress = events.stream().filter(e -> e.name().equals("progress")).toList();
    assertThat(progress).extracting(e -> e.data().get("step").asText())
        .containsExactly("PREPARE", "AI_REQUEST", "VALIDATE", "SAVE", "DONE");
    assertThat(progress).extracting(e -> e.data().get("percent").asInt()).isSorted();
    assertThat(events.get(events.size() - 1).name()).isEqualTo("result");

    JsonNode report = events.get(events.size() - 1).data().get("report");
    assertThat(report.get("provider").asText()).isEqualTo("fake");
    assertThat(report.get("endpoints")).hasSize(12);
    assertThat(report.get("testOrder")).hasSize(12);

    // 저장된 결과를 다시 조회하면 동일한 보고서가 나와야 한다
    JsonNode saved = send("GET", "/api/specs/" + id + "/analysis");
    assertThat(saved.get("_status").asInt()).isEqualTo(200);
    assertThat(saved.get("data").get("report")).isEqualTo(report);

    // 명세 상태가 ANALYZED로 전이되어 3단계가 열린다
    JsonNode spec = send("GET", "/api/specs/" + id);
    assertThat(spec.get("data").get("summary").get("status").asText()).isEqualTo("ANALYZED");
  }

  @Test
  void 실행_순서는_생성이_먼저고_삭제가_마지막이다() throws Exception {
    long id = registerSample("shop-sample");
    http.send(HttpRequest.newBuilder(URI.create(url("/api/specs/" + id + "/analysis")))
        .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());

    JsonNode report = send("GET", "/api/specs/" + id + "/analysis").get("data").get("report");
    List<String> order = new ArrayList<>();
    report.get("testOrder").forEach(n -> order.add(n.asText()));

    assertThat(order.indexOf("createProduct")).isLessThan(order.indexOf("getProduct"));
    assertThat(order.indexOf("createProduct")).isLessThan(order.indexOf("updateProduct"));
    assertThat(order.get(order.size() - 1)).isEqualTo("deleteProduct");
    // 위험도: 삭제는 DESTRUCTIVE, 조회는 SAFE
    report.get("endpoints").forEach(ep -> {
      if (ep.get("operationId").asText().equals("deleteProduct")) {
        assertThat(ep.get("risk").asText()).isEqualTo("DESTRUCTIVE");
      }
      if (ep.get("operationId").asText().equals("listProducts")) {
        assertThat(ep.get("risk").asText()).isEqualTo("SAFE");
      }
    });
  }

  @Test
  void 존재하지_않는_명세는_SSE_시작_전에_일반_404로_응답한다() throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create(url("/api/specs/999999/analysis")))
            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(response.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
  }
}
