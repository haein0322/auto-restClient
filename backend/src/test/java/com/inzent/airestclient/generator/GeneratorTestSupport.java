package com.inzent.airestclient.generator;

import com.inzent.airestclient.dto.ClientDtos.GeneratedFile;
import com.inzent.airestclient.generator.InMemoryCompiler.CompileResult;
import com.inzent.airestclient.service.SwaggerParseService;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 생성기 테스트 공용 도우미: 명세 → 생성 → 컴파일 */
final class GeneratorTestSupport {

  record Generated(ClientCodeGenerator.Output output, CompileResult compile) {

    String file(String suffix) {
      return output.files().stream().filter(f -> f.path().endsWith(suffix)).findFirst()
          .orElseThrow(() -> new AssertionError("생성된 파일이 없습니다: " + suffix)).content();
    }

    boolean hasFile(String suffix) {
      return output.files().stream().anyMatch(f -> f.path().endsWith(suffix));
    }
  }

  private GeneratorTestSupport() {}

  static Generated generateFromFile(String sampleFile, String basePackage) throws IOException {
    return generateFromContent(Files.readString(Path.of("..", "samples", sampleFile)), basePackage);
  }

  static Generated generateFromContent(String content, String basePackage) {
    SwaggerParseService parser = new SwaggerParseService();
    OpenAPI api = parser.parseModel(content);

    // 2단계 분석 결과 대신 operationId를 그대로 메서드명으로 사용한다.
    Map<String, String> methodNames = new LinkedHashMap<>();
    parser.parse(content).endpoints().forEach(ep ->
        methodNames.put(ep.method() + " " + ep.path(), JavaNaming.toCamelCase(ep.operationId())));

    ClientCodeGenerator.Output output = new ClientCodeGenerator().generate(api, methodNames, basePackage);
    Map<String, String> sources = new LinkedHashMap<>();
    for (GeneratedFile file : output.files()) {
      if (file.path().endsWith(".java")) {
        sources.put(file.path().replace("src/main/java/", ""), file.content());
      }
    }
    return new Generated(output, new InMemoryCompiler().compile(sources));
  }
}
