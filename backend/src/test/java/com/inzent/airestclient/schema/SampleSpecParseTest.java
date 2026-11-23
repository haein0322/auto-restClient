package com.inzent.airestclient.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 샘플 명세 3종이 의도한 대로(정상 2종, 오류 1종) 파싱되는지 확인한다. */
class SampleSpecParseTest {

  private SwaggerParseResult parse(String fileName) throws Exception {
    String content = Files.readString(Path.of("..", "samples", fileName));
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    return new OpenAPIParser().readContents(content, null, options);
  }

  @Test
  void 쇼핑몰_샘플은_오류없이_파싱되고_엔드포인트는_12개다() throws Exception {
    SwaggerParseResult result = parse("shop-sample.yaml");
    assertThat(result.getMessages()).isEmpty();

    OpenAPI openApi = result.getOpenAPI();
    long operations = openApi.getPaths().values().stream()
        .mapToLong(item -> item.readOperations().size()).sum();
    assertThat(operations).isEqualTo(12);
    assertThat(openApi.getComponents().getSchemas()).containsKeys("Product", "Order", "ErrorResponse");
  }

  @Test
  void Swagger2_샘플도_OpenAPI3_모델로_변환되어_파싱된다() throws Exception {
    SwaggerParseResult result = parse("legacy-v2-sample.json");
    assertThat(result.getMessages()).isEmpty();
    assertThat(result.getOpenAPI().getPaths()).containsKeys("/memos", "/memos/{memoId}");
  }

  @Test
  void 오류_샘플은_파싱_메시지를_남긴다() throws Exception {
    SwaggerParseResult result = parse("invalid-sample.yaml");
    assertThat(result.getMessages()).isNotEmpty();
  }
}
