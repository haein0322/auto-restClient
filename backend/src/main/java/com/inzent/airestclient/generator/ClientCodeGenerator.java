package com.inzent.airestclient.generator;

import com.inzent.airestclient.dto.ClientDtos.GeneratedFile;
import com.inzent.airestclient.dto.ClientDtos.MethodSpec;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OpenAPI 모델 → RestClient 클라이언트 소스 일체(ApiClient, ApiException, model/*, README)를 만드는 진입점.
 * 결정적 템플릿 방식이라 같은 입력이면 항상 같은 결과가 나온다.
 */
@Component
public class ClientCodeGenerator {

  /** 생성 결과. warnings는 지원하지 않아 건너뛰거나 대체한 항목 목록. */
  public record Output(String basePackage, List<GeneratedFile> files, List<String> warnings,
      List<MethodSpec> methods) {}

  /**
   * @param methodNames "METHOD 경로" → 메서드명(2단계 분석에서 검증된 이름)
   */
  public Output generate(OpenAPI api, Map<String, String> methodNames, String basePackage) {
    List<String> warnings = new ArrayList<>();
    String sourceRoot = "src/main/java/" + basePackage.replace('.', '/') + "/";

    ModelGenerator models = new ModelGenerator(api, basePackage + ".model", warnings);
    models.generateComponents();

    ClientGenerator client = new ClientGenerator(api, models, basePackage, methodNames, warnings);
    // ApiClient를 먼저 만들어야 인라인 스키마(요청/응답)에서 생기는 모델이 models에 모두 등록된다.
    String apiClient = client.apiClientSource();

    List<GeneratedFile> files = new ArrayList<>();
    files.add(new GeneratedFile(sourceRoot + "ApiClient.java", apiClient));
    files.add(new GeneratedFile(sourceRoot + "ApiException.java", client.apiExceptionSource()));
    String modelRoot = sourceRoot + "model/";
    models.sources().forEach((className, source) ->
        files.add(new GeneratedFile(modelRoot + className + ".java", source)));
    files.add(new GeneratedFile("README.md", readme(api, basePackage, client.firstMethodExample())));

    return new Output(basePackage, files, warnings, client.methodSpecs());
  }

  private static String readme(OpenAPI api, String basePackage, String example) {
    String title = api.getInfo() != null && api.getInfo().getTitle() != null ? api.getInfo().getTitle() : "API";
    return """
        # %s 클라이언트

        Swagger/OpenAPI 명세에서 자동 생성된 Spring `RestClient` 기반 Java 클라이언트입니다.

        ## 요구 사항
        - Java 17 이상
        - Spring Framework 6.1 이상 (`spring-web`)
        - Jackson (`jackson-databind`, `jackson-datatype-jsr310`)

        ```groovy
        implementation 'org.springframework:spring-web:6.1.+'
        implementation 'com.fasterxml.jackson.core:jackson-databind'
        implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
        ```

        ## 사용 예
        ```java
        import %s.ApiClient;

        ApiClient client = ApiClient.create("https://api.example.com");
        %s
        ```

        - 4xx/5xx 응답은 `ApiException`(`getStatusCode()`, `getResponseBody()`)으로 던져집니다.
        - 타임아웃·인터셉터가 필요하면 직접 만든 `RestClient`를 `new ApiClient(restClient)`로 전달하세요.
        """.formatted(title, basePackage, example == null ? "// 생성된 메서드가 없습니다." : example);
  }
}
