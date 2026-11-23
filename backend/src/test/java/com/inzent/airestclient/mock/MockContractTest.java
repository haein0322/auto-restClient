package com.inzent.airestclient.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 계약 테스트: 내장 Mock 서버의 실제 HTTP 응답이 샘플 명세(samples/shop-sample.yaml)의
 * 상태코드와 응답 스키마를 따르는지 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static OpenApiSchemaValidator validator;

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate rest;

  @BeforeAll
  static void loadSpec() throws IOException {
    // 테스트 실행 위치(backend/)를 기준으로 상위의 samples 디렉터리를 참조한다.
    Path spec = Path.of("..", "samples", "shop-sample.yaml");
    validator = OpenApiSchemaValidator.fromContent(Files.readString(spec));
  }

  /** 호출 후 기대 상태코드를 확인하고, 명세에 응답 스키마가 있으면 본문까지 검증한다. */
  private JsonNode call(HttpMethod method, String specPath, String actualPath, Object body,
      boolean withKey, int expectedStatus) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (withKey) {
      headers.set("X-API-KEY", "demo-key");
    }
    String json = body == null ? null : MAPPER.writeValueAsString(body);
    ResponseEntity<String> res = rest.exchange(
        "http://localhost:" + port + "/mock" + actualPath, method, new HttpEntity<>(json, headers),
        String.class);

    assertThat(res.getStatusCode().value()).as("%s %s 상태코드", method, actualPath)
        .isEqualTo(expectedStatus);

    String responseBody = res.getBody();
    if (responseBody == null || responseBody.isBlank()) {
      return null;
    }
    JsonNode node = MAPPER.readTree(responseBody);
    validator.responseSchema(specPath, method.name(), expectedStatus).ifPresent(schema ->
        assertThat(validator.validate(schema, node))
            .as("%s %s 응답이 명세(%d)와 일치", method, specPath, expectedStatus).isEmpty());
    return node;
  }

  private long createProduct(String name, int stock) throws IOException {
    JsonNode created = call(HttpMethod.POST, "/products", "/products",
        java.util.Map.of("name", name, "price", 1000, "category", "BOOKS", "stock", stock),
        true, 201);
    return created.get("id").asLong();
  }

  private long createCustomer() throws IOException {
    JsonNode created = call(HttpMethod.POST, "/customers", "/customers",
        java.util.Map.of("name", "테스터", "email", UUID.randomUUID() + "@example.com"), true, 201);
    return created.get("id").asLong();
  }

  @Test
  void 상품_목록은_인증없이_조회되고_시드데이터가_있다() throws IOException {
    JsonNode page = call(HttpMethod.GET, "/products", "/products", null, false, 200);
    assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void 상품_목록_잘못된_페이징은_400() throws IOException {
    JsonNode error = call(HttpMethod.GET, "/products", "/products?size=0", null, false, 400);
    assertThat(error.get("code").asText()).isEqualTo("INVALID_PAGING");
  }

  @Test
  void 상품_카테고리_필터() throws IOException {
    JsonNode page = call(HttpMethod.GET, "/products", "/products?category=FOOD", null, false, 200);
    page.get("items").forEach(item -> assertThat(item.get("category").asText()).isEqualTo("FOOD"));
  }

  @Test
  void 인증_키가_없으면_401() throws IOException {
    JsonNode error = call(HttpMethod.POST, "/products", "/products",
        java.util.Map.of("name", "x", "price", 1, "category", "BOOKS"), false, 401);
    assertThat(error.get("code").asText()).isEqualTo("UNAUTHORIZED");
    call(HttpMethod.GET, "/customers", "/customers", null, false, 401);
  }

  @Test
  void 상품_등록_조회_수정_삭제_흐름() throws IOException {
    long id = createProduct("계약 테스트 상품", 5);
    JsonNode found = call(HttpMethod.GET, "/products/{productId}", "/products/" + id, null, false, 200);
    assertThat(found.get("name").asText()).isEqualTo("계약 테스트 상품");

    JsonNode updated = call(HttpMethod.PUT, "/products/{productId}", "/products/" + id,
        java.util.Map.of("name", "수정됨", "price", 2000, "category", "FASHION", "stock", 9),
        true, 200);
    assertThat(updated.get("stock").asInt()).isEqualTo(9);

    call(HttpMethod.DELETE, "/products/{productId}", "/products/" + id, null, true, 204);
    call(HttpMethod.GET, "/products/{productId}", "/products/" + id, null, false, 404);
  }

  @Test
  void 상품_등록_검증_실패는_400() throws IOException {
    call(HttpMethod.POST, "/products", "/products",
        java.util.Map.of("name", "음수가격", "price", -1, "category", "BOOKS"), true, 400);
    call(HttpMethod.POST, "/products", "/products",
        java.util.Map.of("name", "카테고리오류", "price", 1, "category", "UNKNOWN"), true, 400);
  }

  @Test
  void 고객_이메일_중복은_409_형식오류는_400() throws IOException {
    String email = UUID.randomUUID() + "@example.com";
    call(HttpMethod.POST, "/customers", "/customers",
        java.util.Map.of("name", "중복", "email", email), true, 201);
    JsonNode conflict = call(HttpMethod.POST, "/customers", "/customers",
        java.util.Map.of("name", "중복", "email", email), true, 409);
    assertThat(conflict.get("code").asText()).isEqualTo("DUPLICATED_EMAIL");

    call(HttpMethod.POST, "/customers", "/customers",
        java.util.Map.of("name", "형식오류", "email", "not-an-email"), true, 400);
    call(HttpMethod.GET, "/customers/{customerId}", "/customers/999999", null, true, 404);
    call(HttpMethod.GET, "/customers", "/customers", null, true, 200);
  }

  @Test
  void 주문_생성시_재고가_차감되고_취소시_복원된다() throws IOException {
    long productId = createProduct("주문용 상품", 10);
    long customerId = createCustomer();

    JsonNode order = call(HttpMethod.POST, "/orders", "/orders",
        java.util.Map.of("customerId", customerId,
            "items", List.of(java.util.Map.of("productId", productId, "quantity", 3))),
        true, 201);
    long orderId = order.get("id").asLong();
    assertThat(order.get("totalAmount").asInt()).isEqualTo(3000);
    assertThat(order.get("status").asText()).isEqualTo("CREATED");

    JsonNode afterOrder = call(HttpMethod.GET, "/products/{productId}", "/products/" + productId, null, false, 200);
    assertThat(afterOrder.get("stock").asInt()).isEqualTo(7);

    call(HttpMethod.GET, "/orders/{orderId}", "/orders/" + orderId, null, true, 200);
    call(HttpMethod.GET, "/orders", "/orders?status=CREATED", null, true, 200);

    JsonNode cancelled = call(HttpMethod.POST, "/orders/{orderId}/cancel", "/orders/" + orderId + "/cancel", null, true, 200);
    assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    call(HttpMethod.POST, "/orders/{orderId}/cancel", "/orders/" + orderId + "/cancel", null, true, 409);

    JsonNode restored = call(HttpMethod.GET, "/products/{productId}", "/products/" + productId, null, false, 200);
    assertThat(restored.get("stock").asInt()).isEqualTo(10);
  }

  @Test
  void 주문_오류_케이스_재고부족_409_존재하지않는_대상_404_검증실패_400() throws IOException {
    long productId = createProduct("재고적은 상품", 2);
    long customerId = createCustomer();

    call(HttpMethod.POST, "/orders", "/orders",
        java.util.Map.of("customerId", customerId,
            "items", List.of(java.util.Map.of("productId", productId, "quantity", 5))),
        true, 409);
    call(HttpMethod.POST, "/orders", "/orders",
        java.util.Map.of("customerId", 999999,
            "items", List.of(java.util.Map.of("productId", productId, "quantity", 1))),
        true, 404);
    call(HttpMethod.POST, "/orders", "/orders",
        java.util.Map.of("customerId", customerId, "items", List.of()), true, 400);
    call(HttpMethod.GET, "/orders/{orderId}", "/orders/999999", null, true, 404);
  }

  @Test
  void 응답지연_헤더가_동작한다() throws IOException {
    long start = System.nanoTime();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Mock-Delay-Ms", "300");
    ResponseEntity<String> res = rest.exchange("http://localhost:" + port + "/mock/products",
        HttpMethod.GET, new HttpEntity<>(headers), String.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
  }
}
