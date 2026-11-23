package com.inzent.airestclient.sample;

import com.inzent.airestclient.sample.SampleResolver.Target;
import com.inzent.airestclient.schema.OpenApiSchemaValidator;
import com.inzent.airestclient.service.SwaggerParseService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 샘플 테스트 공용 도우미: 명세 로딩 → OpenAPI 모델, 검증기, 대상 목록 */
final class SampleTestSupport {

  record Loaded(OpenAPI api, OpenApiSchemaValidator validator, List<Target> targets) {

    Target target(String operationId) {
      return targets.stream().filter(t -> t.operationId().equals(operationId)).findFirst().orElseThrow();
    }

    SampleValidator sampleValidator() {
      return new SampleValidator(validator, new DefaultSampleBuilder(api));
    }
  }

  private SampleTestSupport() {}

  static Loaded loadFile(String sampleFile) throws IOException {
    return load(Files.readString(Path.of("..", "samples", sampleFile)));
  }

  static Loaded load(String content) throws IOException {
    SwaggerParseService parser = new SwaggerParseService();
    OpenAPI api = parser.parseModel(content);
    List<Target> targets = new ArrayList<>();
    parser.parse(content).endpoints().forEach(ep -> {
      PathItem item = api.getPaths().get(ep.path());
      targets.add(new Target(ep.operationId(), ep.method(), ep.path(),
          item.readOperationsMap().get(PathItem.HttpMethod.valueOf(ep.method())), item));
    });
    return new Loaded(api, OpenApiSchemaValidator.fromContent(content), targets);
  }
}
