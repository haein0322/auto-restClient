package com.inzent.airestclient.service;

import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.domain.ApiSpec;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.ClientDtos.ClientPayload;
import com.inzent.airestclient.dto.ClientDtos.ClientView;
import com.inzent.airestclient.dto.ClientDtos.GeneratedFile;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.generator.ClientCodeGenerator;
import com.inzent.airestclient.generator.InMemoryCompiler;
import com.inzent.airestclient.generator.InMemoryCompiler.CompileResult;
import com.inzent.airestclient.generator.JavaNaming;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 3단계: RestClient 코드 생성 오케스트레이션.
 * 분석 결과의 메서드명으로 소스를 생성하고, 메모리에서 컴파일해 검증한 뒤 저장한다.
 * CPU를 쓰는 생성·컴파일 동안 DB 트랜잭션을 잡지 않도록 저장만 {@link GeneratedClientStore}가 처리한다.
 */
@Service
public class ClientGenerationService {

  private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

  private final SpecService specService;
  private final SwaggerParseService parseService;
  private final AnalysisResultStore analysisStore;
  private final ClientCodeGenerator generator;
  private final InMemoryCompiler compiler;
  private final GeneratedClientStore store;
  private final String defaultPackage;

  public ClientGenerationService(SpecService specService, SwaggerParseService parseService,
      AnalysisResultStore analysisStore, ClientCodeGenerator generator, InMemoryCompiler compiler,
      GeneratedClientStore store,
      @Value("${app.generator.base-package:com.generated.client}") String defaultPackage) {
    this.specService = specService;
    this.parseService = parseService;
    this.analysisStore = analysisStore;
    this.generator = generator;
    this.compiler = compiler;
    this.store = store;
    this.defaultPackage = defaultPackage;
  }

  public ClientView generate(Long specId, String requestedPackage) {
    String basePackage = resolvePackage(requestedPackage);
    ApiSpec spec = specService.getOrThrow(specId);
    AnalysisReport report = requireAnalysis(specId);

    ClientCodeGenerator.Output output;
    CompileResult compile;
    try {
      OpenAPI api = parseService.parseModel(spec.getContent());
      output = generator.generate(api, methodNames(specId, report), basePackage);
      compile = compiler.compile(javaSources(output.files()));
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.GENERATION_FAILED,
          "코드 생성 중 오류가 발생했습니다: " + e.getClass().getSimpleName(), e);
    }

    return store.save(specId,
        new ClientPayload(basePackage, output.files(), output.warnings(), compile.diagnostics(), output.methods()),
        compile.success());
  }

  public ClientView latest(Long specId) {
    specService.getOrThrow(specId);
    return store.latest(specId);
  }

  /** 최신 생성 결과를 ZIP으로 묶는다. 컴파일에 실패한 결과는 내려받을 수 없다. */
  public byte[] zip(Long specId) {
    ClientView view = latest(specId);
    if (!view.compiled()) {
      throw new BusinessException(ErrorCode.STEP_NOT_READY, "컴파일에 실패한 클라이언트는 내려받을 수 없습니다.");
    }
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (GeneratedFile file : view.files()) {
        zip.putNextEntry(new ZipEntry(file.path()));
        zip.write(file.content().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.finish();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ZIP 파일을 만들지 못했습니다.", e);
    }
  }

  // ---------------- 내부 ----------------

  /** 패키지명은 소스 경로와 ZIP 엔트리 경로에 쓰이므로 엄격하게 검증한다(경로 조작 방지). */
  private String resolvePackage(String requested) {
    String name = requested == null || requested.isBlank() ? defaultPackage : requested.trim();
    boolean valid = name.length() <= 100 && PACKAGE_NAME.matcher(name).matches()
        && java.util.Arrays.stream(name.split("\\.")).noneMatch(JavaNaming.KEYWORDS::contains);
    if (!valid) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST,
          "패키지명이 올바르지 않습니다. 소문자 영문/숫자/밑줄을 점으로 구분하세요(예: com.example.shop).");
    }
    return name;
  }

  private AnalysisReport requireAnalysis(Long specId) {
    try {
      return analysisStore.latest(specId).report();
    } catch (BusinessException e) {
      if (e.getErrorCode() == ErrorCode.ANALYSIS_NOT_FOUND) {
        throw new BusinessException(ErrorCode.STEP_NOT_READY, "AI 명세 분석을 먼저 완료해야 코드를 생성할 수 있습니다.");
      }
      throw e;
    }
  }

  /** "METHOD 경로" → 분석 단계에서 검증된 메서드명 */
  private Map<String, String> methodNames(Long specId, AnalysisReport report) {
    Map<String, String> nameByOperationId = report.endpoints().stream()
        .collect(Collectors.toMap(EndpointInsight::operationId, EndpointInsight::suggestedMethodName,
            (a, b) -> a));
    List<EndpointInfo> endpoints = specService.detail(specId).endpoints();
    Map<String, String> result = new LinkedHashMap<>();
    for (EndpointInfo ep : endpoints) {
      String name = nameByOperationId.get(ep.operationId());
      if (name != null) {
        result.put(ep.method() + " " + ep.path(), name);
      }
    }
    return result;
  }

  private static Map<String, String> javaSources(List<GeneratedFile> files) {
    return files.stream().filter(f -> f.path().endsWith(".java")).collect(Collectors.toMap(
        f -> f.path().replace("src/main/java/", ""), GeneratedFile::content, (a, b) -> a, LinkedHashMap::new));
  }
}
