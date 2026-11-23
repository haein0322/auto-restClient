package com.inzent.airestclient.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.ai.AiClient.SampleGenerationInput;
import com.inzent.airestclient.ai.AiClient.SampleTarget;
import com.inzent.airestclient.ai.AiClient.SpecAnalysisInput;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.AnalysisDtos.AuthAnalysis;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.SampleDtos.AiSampleItem;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.SampleDtos.NameValue;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 실제 Anthropic API 없이, 로컬 가짜 서버로 SDK 연동(요청 형태·인증 헤더·응답 파싱·오류 처리)을 검증한다.
 * 실제 Claude 응답 품질은 이 테스트로 보증되지 않는다(별도 스모크 테스트 필요).
 */
class ClaudeAiClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
  private volatile int responseStatus = 200;
  private volatile String responseBody;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(responseStatus, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private ClaudeAiClient client() {
    return new ClaudeAiClient("test-key-123", "claude-opus-5-5",
        "http://127.0.0.1:" + server.getAddress().getPort(), 10, 4000);
  }

  private static SpecAnalysisInput input() {
    EndpointInfo ep = new EndpointInfo("listProducts", "GET", "/products", "상품 목록", "Product", false,
        List.of(), false, false, List.of("200"));
    return new SpecAnalysisInput("샘플", "설명", List.of(), List.of(), List.of(ep));
  }

  private static String messageResponse(String stopReason, String text) throws Exception {
    return MAPPER.writeValueAsString(java.util.Map.of(
        "id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
        "content", List.of(java.util.Map.of("type", "text", "text", text)),
        "stop_reason", stopReason, "stop_sequence", MAPPER.nullNode(),
        "usage", java.util.Map.of("input_tokens", 10, "output_tokens", 20)));
  }

  @Test
  void 구조화_출력을_파싱하고_요청에_모델_스키마_인증키를_담는다() throws Exception {
    AiAnalysisOutput expected = new AiAnalysisOutput("요약입니다", new AuthAnalysis(false, "", "공개"),
        List.of(new EndpointInsight("listProducts", "listProducts", "목록 조회", RiskLevel.SAFE, "")),
        List.of(), List.of("주의"));
    responseBody = messageResponse("end_turn", MAPPER.writeValueAsString(expected));

    AiAnalysisOutput actual = client().analyze(input());

    assertThat(actual).isEqualTo(expected);
    // SDK 3.x는 키를 Authorization: Bearer 헤더로 전송한다
    assertThat(apiKeyHeader.get()).isEqualTo("Bearer test-key-123");
    String body = requestBody.get();
    assertThat(body).contains("\"model\":\"claude-opus-5-5\"");
    // 구조화 출력 스키마가 요청에 포함되고, 금지된 방식(강제 tool_choice, 비활성 thinking)은 쓰지 않는다
    assertThat(body).contains("output_config").contains("suggestedMethodName");
    assertThat(body).doesNotContain("tool_choice").doesNotContain("budget_tokens");
    // 시스템 프롬프트와 명세 본문이 전달된다
    assertThat(body).contains("api_spec").contains("listProducts");
  }

  @Test
  void 안전_정책_거부는_AI_FAILED로_변환한다() throws Exception {
    responseBody = messageResponse("refusal", "{}");
    assertThatThrownBy(() -> client().analyze(input()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("거부")
        .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.AI_FAILED);
  }

  @Test
  void 응답이_잘리면_AI_FAILED로_변환한다() throws Exception {
    responseBody = messageResponse("max_tokens", "{\"summary\":\"잘림");
    assertThatThrownBy(() -> client().analyze(input()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("끊겼습니다");
  }

  @Test
  void API_오류는_상태코드만_노출하고_본문은_숨긴다() {
    responseStatus = 401;
    responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key SECRET-DETAIL\"}}";

    assertThatThrownBy(() -> client().analyze(input()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageNotContaining("SECRET-DETAIL")
        .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.AI_FAILED);
  }

  @Test
  void API_키가_없으면_기동_시점에_명확히_실패한다() {
    assertThatThrownBy(() -> new ClaudeAiClient("", "claude-opus-5-5", "", 10, 4000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  void 샘플_생성_요청은_스키마와_검증_오류_피드백을_담고_응답을_파싱한다() throws Exception {
    AiSampleOutput expected = new AiSampleOutput(List.of(new AiSampleItem("createProduct",
        List.of(), List.of(new NameValue("page", "1")), List.of(), "{\"name\":\"무선 이어폰\"}")));
    responseBody = messageResponse("end_turn", MAPPER.writeValueAsString(expected));

    SampleGenerationInput input = new SampleGenerationInput("샘플",
        List.of(new SampleTarget("createProduct", "POST", "/products", "{\"body\":{\"type\":\"object\"}}")),
        java.util.Map.of("createProduct", List.of("body.price: 0 이상이어야 합니다")));

    AiSampleOutput actual = client().generateSamples(input);

    assertThat(actual).isEqualTo(expected);
    String body = requestBody.get();
    assertThat(body).contains("output_config").contains("bodyJson").contains("createProduct");
    // 이전 시도의 검증 오류가 별도 태그로 전달되어야 한다
    assertThat(body).contains("validation_errors").contains("body.price");
    assertThat(body).doesNotContain("tool_choice");
  }
}
