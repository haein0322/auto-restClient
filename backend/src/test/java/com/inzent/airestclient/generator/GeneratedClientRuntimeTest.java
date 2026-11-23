package com.inzent.airestclient.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.airestclient.generator.GeneratorTestSupport.Generated;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 생성 → 메모리 컴파일 → 격리 ClassLoader 로딩 → 내장 Mock 서버 실제 HTTP 호출까지 검증한다.
 * "컴파일된다"를 넘어 "생성된 클라이언트가 실제로 올바르게 통신한다"를 보증하는 테스트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeneratedClientRuntimeTest {

  private static final String PACKAGE = "com.example.shop";
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private static ClassLoader loader;
  private static Class<?> clientClass;

  @LocalServerPort
  private int port;

  @BeforeAll
  static void generateAndLoad() throws Exception {
    Generated generated = GeneratorTestSupport.generateFromFile("shop-sample.yaml", PACKAGE);
    assertThat(generated.compile().diagnostics()).isEmpty();
    loader = generated.compile().classes().newClassLoader(GeneratedClientRuntimeTest.class.getClassLoader());
    clientClass = loader.loadClass(PACKAGE + ".ApiClient");
  }

  private Object newClient(String apiKey) throws Exception {
    String baseUrl = "http://localhost:" + port + "/mock";
    return apiKey == null
        ? clientClass.getMethod("create", String.class).invoke(null, baseUrl)
        : clientClass.getMethod("create", String.class, String.class).invoke(null, baseUrl, apiKey);
  }

  /** 이름으로 메서드를 찾아, 각 인자를 메서드의 파라미터 타입(생성된 클래스)으로 변환해 호출하고 JSON 트리로 반환한다. */
  private JsonNode call(Object client, String method, Object... args) throws Exception {
    Method target = List.of(clientClass.getMethods()).stream()
        .filter(m -> m.getName().equals(method)).findFirst()
        .orElseThrow(() -> new AssertionError("생성된 메서드가 없습니다: " + method));
    Object[] converted = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      converted[i] = args[i] == null ? null : MAPPER.convertValue(args[i], target.getParameterTypes()[i]);
    }
    try {
      Object result = target.invoke(client, converted);
      return result == null ? null : MAPPER.valueToTree(result);
    } catch (InvocationTargetException e) {
      throw unwrap(e);
    }
  }

  private static Exception unwrap(InvocationTargetException e) {
    return e.getCause() instanceof Exception cause ? cause : e;
  }

  /** 생성된 ApiException의 상태 코드/본문을 읽는다(클래스가 격리 ClassLoader에 있으므로 리플렉션). */
  private record ApiError(int status, String body) {}

  private ApiError expectApiException(Exception e) throws Exception {
    assertThat(e.getClass().getName()).isEqualTo(PACKAGE + ".ApiException");
    return new ApiError((Integer) e.getClass().getMethod("getStatusCode").invoke(e),
        (String) e.getClass().getMethod("getResponseBody").invoke(e));
  }

  @Test
  void 공개_API는_인증없이_조회되고_쿼리_파라미터가_전달된다() throws Exception {
    Object client = newClient(null);

    JsonNode all = call(client, "listProducts", null, null, null);
    assertThat(all.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);

    // enum 쿼리 파라미터(category=FOOD)와 페이징이 서버에 실제로 전달되어야 한다
    JsonNode foods = call(client, "listProducts", 0, 2, "FOOD");
    assertThat(foods.get("size").asInt()).isEqualTo(2);
    foods.get("items").forEach(item -> assertThat(item.get("category").asText()).isEqualTo("FOOD"));
  }

  @Test
  void 상품_등록_조회_수정_삭제와_오류_변환() throws Exception {
    Object client = newClient("demo-key");

    JsonNode created = call(client, "createProduct",
        Map.of("name", "생성 클라이언트 상품", "price", 1500, "category", "BOOKS", "stock", 7));
    long id = created.get("id").asLong();
    assertThat(created.get("createdAt").asText()).isNotBlank(); // OffsetDateTime 역직렬화

    JsonNode found = call(client, "getProduct", id);
    assertThat(found.get("name").asText()).isEqualTo("생성 클라이언트 상품");
    assertThat(found.get("stock").asInt()).isEqualTo(7);

    JsonNode updated = call(client, "updateProduct", id,
        Map.of("name", "수정됨", "price", 2000, "category", "FASHION", "stock", 3));
    assertThat(updated.get("category").asText()).isEqualTo("FASHION");

    assertThat(call(client, "deleteProduct", id)).isNull(); // 204 → void

    Exception notFound = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> call(client, "getProduct", id));
    ApiError error = expectApiException(notFound);
    assertThat(error.status()).isEqualTo(404);
    assertThat(error.body()).contains("PRODUCT_NOT_FOUND");
  }

  @Test
  void 인증_키가_없으면_ApiException_401() throws Exception {
    Object client = newClient(null);
    Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> call(client, "listCustomers"));
    ApiError error = expectApiException(e);
    assertThat(error.status()).isEqualTo(401);
    assertThat(error.body()).contains("UNAUTHORIZED");
  }

  @Test
  void 검증_실패는_ApiException_400() throws Exception {
    Object client = newClient("demo-key");
    Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> call(client, "createProduct", Map.of("name", "음수", "price", -5, "category", "BOOKS")));
    assertThat(expectApiException(e).status()).isEqualTo(400);
  }

  @Test
  void 고객과_주문_흐름_배열_본문과_제네릭_응답() throws Exception {
    Object client = newClient("demo-key");

    JsonNode customer = call(client, "createCustomer",
        Map.of("name", "홍길동", "email", UUID.randomUUID() + "@example.com", "grade", "VIP"));
    long customerId = customer.get("id").asLong();
    assertThat(customer.get("grade").asText()).isEqualTo("VIP");

    JsonNode customers = call(client, "listCustomers"); // List<Customer>
    assertThat(customers.isArray()).isTrue();
    assertThat(customers.size()).isGreaterThanOrEqualTo(1);

    JsonNode product = call(client, "createProduct",
        Map.of("name", "주문 대상", "price", 1000, "category", "FOOD", "stock", 10));
    long productId = product.get("id").asLong();

    JsonNode order = call(client, "createOrder", Map.of("customerId", customerId,
        "items", List.of(Map.of("productId", productId, "quantity", 3))));
    long orderId = order.get("id").asLong();
    assertThat(order.get("totalAmount").asInt()).isEqualTo(3000);
    assertThat(order.get("status").asText()).isEqualTo("CREATED");

    assertThat(call(client, "getProduct", productId).get("stock").asInt()).isEqualTo(7);

    JsonNode cancelled = call(client, "cancelOrder", orderId);
    assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

    Exception conflict = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> call(client, "cancelOrder", orderId));
    assertThat(expectApiException(conflict).status()).isEqualTo(409);
    assertThat(call(client, "listOrders", "CANCELLED").size()).isGreaterThanOrEqualTo(1);
  }
}
