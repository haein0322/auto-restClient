package com.inzent.airestclient.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.sample.SampleResolver.Target;
import com.inzent.airestclient.sample.SampleTestSupport.Loaded;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 규칙 기반 샘플 생성기와 검증기 */
class RuleBasedSampleTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonNode json(String text) throws Exception {
    return MAPPER.readTree(text);
  }

  private List<String> validate(Loaded loaded, String operationId, JsonNode request) {
    Target t = loaded.target(operationId);
    return loaded.sampleValidator().validate(t.path(), t.method(), t.operation(), t.item(), request);
  }

  @Test
  void 쇼핑몰_샘플의_모든_엔드포인트에_유효한_샘플이_생성된다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");
    DefaultSampleBuilder builder = new DefaultSampleBuilder(loaded.api());

    assertThat(loaded.targets()).hasSize(12);
    for (Target t : loaded.targets()) {
      JsonNode request = builder.build(t.operation(), t.item());
      assertThat(validate(loaded, t.operationId(), request)).as("%s 샘플 검증: %s", t.operationId(), request).isEmpty();
    }
  }

  @Test
  void 생성된_샘플의_내용이_스키마_제약을_반영한다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");
    DefaultSampleBuilder builder = new DefaultSampleBuilder(loaded.api());
    java.util.function.Function<String, JsonNode> sample = id -> {
      Target t = loaded.target(id);
      return builder.build(t.operation(), t.item());
    };

    // 경로 ID는 1. 쿼리는 기본값이 있는 것(page, size)만 포함하고 선택 값(category)은 생략
    assertThat(sample.apply("getProduct").get("pathParams").get("productId").asLong()).isEqualTo(1);
    JsonNode listQuery = sample.apply("listProducts").get("queryParams");
    assertThat(listQuery.get("page").asInt()).isEqualTo(0);
    assertThat(listQuery.get("size").asInt()).isEqualTo(10);
    assertThat(listQuery.has("category")).isFalse();

    // 본문: allOf/참조 enum/필수 값(price>=0)
    JsonNode product = sample.apply("createProduct").get("body");
    assertThat(product.get("name").asText()).isNotBlank();
    assertThat(product.get("price").decimalValue().signum()).isGreaterThanOrEqualTo(0);
    assertThat(product.get("category").asText()).isEqualTo("ELECTRONICS"); // enum 첫 값

    // 중첩 배열과 *Id 참조
    JsonNode order = sample.apply("createOrder").get("body");
    assertThat(order.get("customerId").asLong()).isEqualTo(1);
    assertThat(order.get("items")).hasSize(1);
    assertThat(order.get("items").get(0).get("productId").asLong()).isEqualTo(1);
    assertThat(order.get("items").get(0).get("quantity").asInt()).isBetween(1, 100);

    // format: email
    assertThat(sample.apply("createCustomer").get("body").get("email").asText()).contains("@");

    // 같은 입력이면 항상 같은 결과(결정적)
    assertThat(sample.apply("createOrder")).isEqualTo(sample.apply("createOrder"));
  }

  @Test
  void 제약이_까다로운_스키마도_만족한다_순환_참조_포함() throws Exception {
    Loaded loaded = SampleTestSupport.load("""
        openapi: 3.0.3
        info: { title: 제약 테스트, version: '1' }
        paths:
          /things/{thingId}:
            put:
              operationId: putThing
              parameters:
                - { name: thingId, in: path, required: true, schema: { type: string, format: uuid } }
                - { name: level, in: query, required: true, schema: { type: integer, minimum: 50, maximum: 60 } }
                - { name: tags, in: query, required: true, schema: { type: array, minItems: 2, items: { type: string } } }
                - { name: X-Trace, in: header, required: true, schema: { type: string, minLength: 20 } }
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { $ref: '#/components/schemas/Thing' }
              responses:
                '200': { description: ok }
        components:
          schemas:
            Thing:
              type: object
              required: [code, score, ratio, created]
              properties:
                code: { type: string, minLength: 3, maxLength: 5 }
                score: { type: integer, minimum: 1, exclusiveMinimum: true, maximum: 3 }
                ratio: { type: number, minimum: 0.5, maximum: 0.9 }
                created: { type: string, format: date-time }
                state: { type: string, enum: [OPEN, CLOSED] }
                attrs: { type: object, additionalProperties: { type: integer } }
                parent: { $ref: '#/components/schemas/Thing' }
                children: { type: array, items: { $ref: '#/components/schemas/Thing' } }
        """);
    DefaultSampleBuilder builder = new DefaultSampleBuilder(loaded.api());
    Target t = loaded.target("putThing");

    JsonNode request = builder.build(t.operation(), t.item());

    assertThat(validate(loaded, "putThing", request)).isEmpty();
    assertThat(request.get("queryParams").get("tags")).hasSize(2);
    assertThat(request.get("body").get("code").asText().length()).isBetween(3, 5);
    assertThat(request.toString().length()).as("순환 참조가 무한히 커지지 않는다").isLessThan(5000);
  }

  // ---------------- 검증기 ----------------

  @Test
  void 검증기는_본문_위반을_잡는다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");

    List<String> errors = validate(loaded, "createProduct",
        json("{\"body\":{\"name\":\"x\",\"price\":-1,\"category\":\"UNKNOWN\"}}"));

    assertThat(errors).anyMatch(e -> e.startsWith("body.price"));
    assertThat(errors).anyMatch(e -> e.startsWith("body.category"));
  }

  @Test
  void 검증기는_필수_본문과_필수_필드_누락을_잡는다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");

    assertThat(validate(loaded, "createProduct", json("{}"))).anyMatch(e -> e.contains("필수 요청 본문"));
    assertThat(validate(loaded, "createProduct", json("{\"body\":{\"price\":1}}")))
        .anyMatch(e -> e.startsWith("body") && e.contains("name"));
  }

  @Test
  void 검증기는_파라미터_오류를_잡는다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");

    assertThat(validate(loaded, "getProduct", json("{}"))).anyMatch(e -> e.contains("pathParams.productId") && e.contains("필수"));
    assertThat(validate(loaded, "getProduct", json("{\"pathParams\":{\"productId\":\"abc\"}}")))
        .anyMatch(e -> e.startsWith("pathParams.productId"));
    assertThat(validate(loaded, "listProducts", json("{\"queryParams\":{\"size\":500,\"category\":\"NOPE\"}}")))
        .anyMatch(e -> e.startsWith("queryParams.size")).anyMatch(e -> e.startsWith("queryParams.category"));
    assertThat(validate(loaded, "listProducts", json("{\"queryParams\":{\"nothing\":1}}")))
        .anyMatch(e -> e.contains("선언되지 않은"));
  }

  @Test
  void 검증기는_구조_오류를_잡는다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");

    assertThat(validate(loaded, "listProducts", json("[]"))).containsExactly("샘플은 JSON 객체여야 합니다.");
    assertThat(validate(loaded, "listProducts", json("{\"extra\":1}"))).anyMatch(e -> e.contains("알 수 없는 최상위 키"));
    assertThat(validate(loaded, "listProducts", json("{\"body\":{\"a\":1}}"))).anyMatch(e -> e.contains("본문을 받지 않습니다"));
    assertThat(validate(loaded, "getProduct", json("{\"pathParams\":\"x\"}"))).anyMatch(e -> e.contains("객체여야"));
  }

  @Test
  void 올바른_샘플은_통과한다() throws Exception {
    Loaded loaded = SampleTestSupport.loadFile("shop-sample.yaml");

    assertThat(validate(loaded, "createProduct",
        json("{\"body\":{\"name\":\"무선 이어폰\",\"price\":89000,\"category\":\"ELECTRONICS\",\"stock\":5}}"))).isEmpty();
    assertThat(validate(loaded, "listProducts",
        json("{\"queryParams\":{\"page\":0,\"size\":10,\"category\":\"FOOD\"}}"))).isEmpty();
  }
}
