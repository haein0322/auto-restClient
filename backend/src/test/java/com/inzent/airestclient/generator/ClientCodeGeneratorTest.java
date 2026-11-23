package com.inzent.airestclient.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.inzent.airestclient.generator.GeneratorTestSupport.Generated;
import org.junit.jupiter.api.Test;

class ClientCodeGeneratorTest {

  private static void assertCompiles(Generated generated) {
    assertThat(generated.compile().diagnostics()).as("컴파일 오류 없음").isEmpty();
    assertThat(generated.compile().success()).isTrue();
  }

  @Test
  void 쇼핑몰_샘플을_생성하면_컴파일된다() throws Exception {
    Generated g = GeneratorTestSupport.generateFromFile("shop-sample.yaml", "com.example.shop");

    assertCompiles(g);
    assertThat(g.output().warnings()).isEmpty();
    assertThat(g.output().files()).extracting(f -> f.path()).contains(
        "src/main/java/com/example/shop/ApiClient.java",
        "src/main/java/com/example/shop/ApiException.java",
        "src/main/java/com/example/shop/model/Product.java",
        "src/main/java/com/example/shop/model/ProductRequest.java",
        "src/main/java/com/example/shop/model/ProductCategory.java",
        "src/main/java/com/example/shop/model/OrderRequest.java",
        "README.md");
  }

  @Test
  void 모델_매핑_규칙이_적용된다() throws Exception {
    Generated g = GeneratorTestSupport.generateFromFile("shop-sample.yaml", "com.example.shop");

    // allOf(ProductRequest + id, createdAt)는 속성이 병합된 record가 된다
    String product = g.file("model/Product.java");
    assertThat(product).contains("public record Product(")
        .contains("Long id").contains("String name").contains("OffsetDateTime createdAt")
        .contains("BigDecimal price").contains("ProductCategory category").contains("Integer stock");
    // JDK 타입은 FQN이 아니라 import로 정리된다
    assertThat(product).contains("import java.math.BigDecimal;").contains("import java.time.OffsetDateTime;")
        .doesNotContain("java.math.BigDecimal price");

    // enum은 JSON 값과 상수명을 분리한다
    assertThat(g.file("model/ProductCategory.java")).contains("public enum ProductCategory")
        .contains("ELECTRONICS(\"ELECTRONICS\")").contains("@JsonValue");

    // 배열 속성
    assertThat(g.file("model/Order.java")).contains("List<OrderItem> items");
    assertThat(g.file("model/ProductPage.java")).contains("List<Product> items");
  }

  @Test
  void 클라이언트_메서드_시그니처와_본문이_올바르다() throws Exception {
    Generated g = GeneratorTestSupport.generateFromFile("shop-sample.yaml", "com.example.shop");
    String client = g.file("ApiClient.java");

    // 쿼리 파라미터는 선언 순서, 경로 변수가 맨 앞, 본문이 그 다음
    assertThat(client).contains("public ProductPage listProducts(Integer page, Integer size, ProductCategory category)");
    assertThat(client).contains("public Product getProduct(Long productId)");
    assertThat(client).contains("public Product updateProduct(Long productId, ProductRequest body)");
    assertThat(client).contains("public void deleteProduct(Long productId)");
    assertThat(client).contains("public List<Customer> listCustomers()");
    assertThat(client).contains("public Order cancelOrder(Long orderId)");
    assertThat(client).contains("ParameterizedTypeReference<List<Customer>>");
    assertThat(client).contains(".toBodilessEntity()");
    assertThat(client).contains("ApiClient::throwError");

    // API Key 인증 헤더가 생성된다
    assertThat(client).contains("public static ApiClient create(String baseUrl, String apiKey)")
        .contains("defaultHeader(\"X-API-KEY\", apiKey)");
    // 상품 조회는 공개 API여도 인증 인자는 선택 사항이므로 메서드 시그니처에 인증 파라미터가 없다
    assertThat(client).doesNotContain("String xApiKey");
  }

  @Test
  void Swagger2_샘플도_생성되고_컴파일된다() throws Exception {
    Generated g = GeneratorTestSupport.generateFromFile("legacy-v2-sample.json", "com.example.memo");

    assertCompiles(g);
    assertThat(g.file("ApiClient.java")).contains("public List<Memo> listMemos()")
        .contains("public Memo createMemo(MemoRequest body)").contains("public Memo getMemo(Long memoId)");
  }

  @Test
  void 까다로운_스키마도_컴파일_가능한_코드를_만든다() {
    String spec = """
        openapi: 3.0.3
        info: { title: 까다로운 API, version: '1.0' }
        paths:
          /nodes:
            get:
              operationId: list-nodes
              parameters:
                - { name: X-Request-Id, in: header, schema: { type: string } }
                - { name: ids, in: query, schema: { type: array, items: { type: integer } } }
                - { name: class, in: query, schema: { type: string, enum: [a-b, 'c d', '1x'] } }
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema: { type: array, items: { $ref: '#/components/schemas/Node' } }
            post:
              operationId: createNode
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      required: [label]
                      properties:
                        label: { type: string }
                        tags: { type: object, additionalProperties: { type: string } }
              responses:
                '201':
                  description: created
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id: { type: integer, format: int64 }
          /nodes/{node-id}/export:
            get:
              operationId: exportNode
              parameters:
                - { name: node-id, in: path, required: true, schema: { type: string, format: uuid } }
              responses:
                '200':
                  description: text
                  content:
                    text/plain: { schema: { type: string } }
          /upload:
            post:
              operationId: uploadFile
              requestBody:
                content:
                  multipart/form-data:
                    schema: { type: object, properties: { file: { type: string, format: binary } } }
              responses:
                '200': { description: ok }
          /poly:
            get:
              operationId: getPoly
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        oneOf:
                          - $ref: '#/components/schemas/Node'
                          - $ref: '#/components/schemas/List'
        components:
          schemas:
            Node:
              type: object
              description: 자기 참조 */ \\u0041 주석 깨짐 방지
              properties:
                id: { type: integer, format: int64 }
                class: { type: string }
                new-value: { type: number, format: double }
                parent: { $ref: '#/components/schemas/Node' }
                children: { type: array, items: { $ref: '#/components/schemas/Node' } }
                kind: { type: string, enum: [first, second] }
                level: { type: integer, enum: [1, 2, 3] }
                meta:
                  type: object
                  properties:
                    note: { type: string }
            List:
              type: object
              properties:
                size: { type: integer }
            Error:
              type: object
              properties:
                message: { type: string }
            Empty:
              type: object
        """;

    Generated g = GeneratorTestSupport.generateFromContent(spec, "com.example.edge");

    assertCompiles(g);
    // 예약된 이름(List, Error)은 Model 접미사가 붙는다
    assertThat(g.hasFile("model/ListModel.java")).isTrue();
    assertThat(g.hasFile("model/ErrorModel.java")).isTrue();
    // 키워드 속성은 안전한 필드명 + 원래 JSON 이름 보존
    assertThat(g.file("model/Node.java")).contains("@JsonProperty(\"class\") String classCall")
        .contains("@JsonProperty(\"new-value\") Double newValue")
        .contains("Node parent").contains("List<Node> children").contains("Integer level");
    // 인라인 객체/enum은 별도 클래스가 된다
    assertThat(g.hasFile("model/NodeMeta.java")).isTrue();
    assertThat(g.hasFile("model/NodeKind.java")).isTrue();
    // 설명에 주석 종료 토큰이 있어도 컴파일된다
    assertThat(g.file("model/Node.java")).doesNotContain("*/ \\u0041");
    // 지원하지 않는 항목은 경고로 남기고 생성은 계속한다
    assertThat(g.output().warnings()).anyMatch(w -> w.contains("POST /upload") && w.contains("multipart"))
        .anyMatch(w -> w.contains("oneOf"));
    assertThat(g.file("ApiClient.java")).doesNotContain("uploadFile");
    // 텍스트 응답, 헤더 파라미터, 경로 변수명 정리
    assertThat(g.file("ApiClient.java")).contains("public String exportNode(UUID nodeId)")
        .contains("String xRequestId").contains("headers.set(\"X-Request-Id\"");
  }
}
