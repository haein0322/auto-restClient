package com.inzent.airestclient.dto;

import java.time.LocalDateTime;
import java.util.List;

/** RestClient 코드 생성(3단계) 관련 DTO */
public final class ClientDtos {

  private ClientDtos() {}

  /** 생성된 파일 하나(경로는 프로젝트 루트 기준 상대 경로) */
  public record GeneratedFile(String path, String content) {}

  /** 생성 요청. basePackage를 생략하면 서버 기본값을 사용한다. */
  public record GenerateClientRequest(String basePackage) {}

  /** 생성된 메서드의 파라미터 하나. in: path | query | header | body (시그니처 순서와 동일) */
  public record ParamSpec(String in, String name) {}

  /** 생성된 클라이언트 메서드 메타데이터. key는 "METHOD 경로". 통신 테스트가 리플렉션 호출에 사용한다. */
  public record MethodSpec(String key, String methodName, List<ParamSpec> params) {}

  /**
   * DB에 저장되는 생성 결과 전체(files_json 컬럼).
   * methods는 통신 테스트용 메타데이터이며, 이 필드가 없던 이전 버전 결과에서는 null이다.
   */
  public record ClientPayload(
      String basePackage,
      List<GeneratedFile> files,
      List<String> warnings,
      List<String> diagnostics,
      List<MethodSpec> methods) {}

  public record ClientView(
      Long specId,
      LocalDateTime generatedAt,
      boolean compiled,
      String basePackage,
      List<String> diagnostics,
      List<String> warnings,
      List<GeneratedFile> files) {}
}
