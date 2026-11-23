package com.inzent.airestclient.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.inzent.airestclient.ai.AiClient;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.SampleDtos.AiSampleItem;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.SampleDtos.NameValue;
import com.inzent.airestclient.sample.SampleResolver.Resolved;
import com.inzent.airestclient.sample.SampleResolver.Result;
import com.inzent.airestclient.sample.SampleResolver.Target;
import com.inzent.airestclient.sample.SampleTestSupport.Loaded;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** AI 호출 → 서버 검증 → 오류 피드백 재시도 → 규칙 기반 대체 루프를 스텁 AI로 검증한다. */
class SampleResolverTest {

  private static Loaded loaded;

  @BeforeAll
  static void load() throws Exception {
    loaded = SampleTestSupport.loadFile("shop-sample.yaml");
  }

  /** 호출 기록을 남기고 라운드별 응답을 돌려주는 스텁 */
  private static final class StubAi implements AiClient {

    final List<SampleGenerationInput> calls = new ArrayList<>();
    final Function<Integer, AiSampleOutput> responder;
    final String provider;

    StubAi(String provider, Function<Integer, AiSampleOutput> responder) {
      this.provider = provider;
      this.responder = responder;
    }

    @Override
    public String provider() {
      return provider;
    }

    @Override
    public String model() {
      return "stub";
    }

    @Override
    public AiAnalysisOutput analyze(SpecAnalysisInput input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AiSampleOutput generateSamples(SampleGenerationInput input) {
      calls.add(input);
      return responder.apply(calls.size());
    }

    @Override
    public com.inzent.airestclient.dto.TestDtos.AiDiagnosisOutput diagnose(DiagnosisInput input) {
      throw new UnsupportedOperationException();
    }
  }

  private static AiSampleItem productItem(String priceJson) {
    return new AiSampleItem("createProduct", List.of(), List.of(), List.of(),
        "{\"name\":\"무선 이어폰\",\"price\":" + priceJson + ",\"category\":\"ELECTRONICS\",\"stock\":3}");
  }

  private Result resolve(StubAi ai, String... operationIds) {
    List<Target> targets = java.util.Arrays.stream(operationIds).map(loaded::target).toList();
    return new SampleResolver(ai).resolve(loaded.api(), loaded.validator(), "쇼핑몰", targets);
  }

  private static Resolved only(Result result) {
    assertThat(result.samples()).hasSize(1);
    return result.samples().get(0);
  }

  @Test
  void fake_제공자는_AI를_호출하지_않고_규칙_기반으로_만든다() {
    StubAi ai = new StubAi("fake", round -> new AiSampleOutput(List.of()));

    Result result = resolve(ai, "createProduct", "getProduct");

    assertThat(ai.calls).isEmpty();
    assertThat(result.samples()).allSatisfy(s -> {
      assertThat(s.source()).isEqualTo("RULE");
      assertThat(s.valid()).isTrue();
    });
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void AI_샘플이_검증을_통과하면_그대로_채택한다() {
    StubAi ai = new StubAi("claude", round -> new AiSampleOutput(List.of(productItem("89000"))));

    Resolved resolved = only(resolve(ai, "createProduct"));

    assertThat(ai.calls).hasSize(1);
    assertThat(resolved.source()).isEqualTo("AI");
    assertThat(resolved.valid()).isTrue();
    assertThat(resolved.request().get("body").get("name").asText()).isEqualTo("무선 이어폰");
  }

  @Test
  void 검증_오류는_AI에게_되돌려_보내고_고쳐진_결과를_채택한다() {
    // 1차: price 음수(위반) → 2차: 올바른 값
    StubAi ai = new StubAi("claude", round ->
        new AiSampleOutput(List.of(productItem(round == 1 ? "-5" : "1500"))));

    Resolved resolved = only(resolve(ai, "createProduct"));

    assertThat(ai.calls).hasSize(2);
    assertThat(ai.calls.get(0).feedback()).isEmpty();
    // 두 번째 호출에는 첫 시도의 검증 오류가 담겨 있어야 한다
    assertThat(ai.calls.get(1).feedback()).containsKey("createProduct");
    assertThat(ai.calls.get(1).feedback().get("createProduct")).anyMatch(e -> e.startsWith("body.price"));
    assertThat(resolved.source()).isEqualTo("AI");
    assertThat(resolved.request().get("body").get("price").asInt()).isEqualTo(1500);
  }

  @Test
  void 끝내_통과하지_못하면_규칙_기반으로_대체하고_경고한다() {
    StubAi ai = new StubAi("claude", round -> new AiSampleOutput(List.of(productItem("-1"))));

    Result result = resolve(ai, "createProduct");
    Resolved resolved = only(result);

    assertThat(ai.calls).hasSize(3); // 최초 1회 + 피드백 재시도 2회
    assertThat(resolved.source()).isEqualTo("FALLBACK");
    assertThat(resolved.valid()).isTrue();
    assertThat(resolved.request().get("body").get("price").decimalValue().signum()).isGreaterThanOrEqualTo(0);
    assertThat(result.warnings()).anyMatch(w -> w.contains("규칙 기반 생성으로 대체"));
  }

  @Test
  void AI_호출이_실패하면_전부_규칙_기반으로_대체한다() {
    StubAi ai = new StubAi("claude", round -> {
      throw new BusinessException(ErrorCode.AI_FAILED, "Claude API 호출이 실패했습니다(HTTP 529).");
    });

    Result result = resolve(ai, "createProduct", "getProduct");

    assertThat(ai.calls).hasSize(1);
    assertThat(result.samples()).allSatisfy(s -> {
      assertThat(s.source()).isEqualTo("FALLBACK");
      assertThat(s.valid()).isTrue();
    });
    assertThat(result.warnings()).singleElement().asString().contains("HTTP 529");
  }

  @Test
  void AI가_누락한_엔드포인트는_피드백_후_대체된다() {
    // createProduct만 응답하고 getProduct는 계속 누락
    StubAi ai = new StubAi("claude", round ->
        new AiSampleOutput(round == 1 ? List.of(productItem("100")) : List.of()));

    Result result = resolve(ai, "createProduct", "getProduct");

    assertThat(result.samples().get(0).source()).isEqualTo("AI");
    assertThat(result.samples().get(1).source()).isEqualTo("FALLBACK");
    assertThat(ai.calls.get(1).targets()).extracting(AiClient.SampleTarget::operationId).containsExactly("getProduct");
    assertThat(ai.calls.get(1).feedback().get("getProduct")).anyMatch(e -> e.contains("샘플이 없습니다"));
  }

  @Test
  void 파라미터_문자열은_스키마_타입으로_변환된다() {
    AiSampleItem list = new AiSampleItem("listProducts", List.of(),
        List.of(new NameValue("page", "2"), new NameValue("size", "5"), new NameValue("category", "FOOD")),
        List.of(), "");
    AiSampleItem get = new AiSampleItem("getProduct", List.of(new NameValue("productId", "7")), List.of(),
        List.of(), "");
    StubAi ai = new StubAi("claude", round -> new AiSampleOutput(List.of(list, get)));

    Result result = resolve(ai, "listProducts", "getProduct");

    assertThat(ai.calls).hasSize(1);
    assertThat(result.samples()).allSatisfy(s -> assertThat(s.source()).isEqualTo("AI"));
    assertThat(result.samples().get(0).request().get("queryParams").get("page").isIntegralNumber()).isTrue();
    assertThat(result.samples().get(1).request().get("pathParams").get("productId").asLong()).isEqualTo(7);
  }

  @Test
  void 잘못된_JSON_본문은_오류로_피드백된다() {
    AiSampleItem broken = new AiSampleItem("createProduct", List.of(), List.of(), List.of(), "{not json");
    StubAi ai = new StubAi("claude", round ->
        new AiSampleOutput(List.of(round == 1 ? broken : productItem("10"))));

    Resolved resolved = only(resolve(ai, "createProduct"));

    assertThat(ai.calls.get(1).feedback().get("createProduct")).anyMatch(e -> e.contains("올바른 JSON이 아닙니다"));
    assertThat(resolved.source()).isEqualTo("AI");
  }

  @Test
  void AI에게_보내는_엔드포인트_설명은_참조를_펼친_스키마다() {
    StubAi ai = new StubAi("claude", round -> new AiSampleOutput(List.of(productItem("10"))));

    resolve(ai, "createProduct");

    String schema = ai.calls.get(0).targets().get(0).schemaJson();
    // $ref가 아니라 실제 속성/제약이 보여야 한다
    assertThat(schema).doesNotContain("$ref").contains("\"name\"").contains("\"minimum\"")
        .contains("ELECTRONICS").contains("maxLength");
  }
}
