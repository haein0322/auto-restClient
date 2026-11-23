package com.inzent.airestclient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 업로드된 Swagger(OpenAPI) 명세 */
@Entity
@Table(name = "api_spec")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiSpec {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  private String version;

  @Column(name = "source_type", nullable = false)
  private String sourceType;

  @Lob
  @Column(nullable = false)
  private String content;

  @Column(name = "content_hash", nullable = false)
  private String contentHash;

  @Column(name = "endpoint_count", nullable = false)
  private int endpointCount;

  @Column(name = "schema_count", nullable = false)
  private int schemaCount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SpecStatus status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public ApiSpec(String title, String version, String sourceType, String content,
      String contentHash, int endpointCount, int schemaCount) {
    this.title = title;
    this.version = version;
    this.sourceType = sourceType;
    this.content = content;
    this.contentHash = contentHash;
    this.endpointCount = endpointCount;
    this.schemaCount = schemaCount;
    this.status = SpecStatus.PARSED;
    this.createdAt = LocalDateTime.now();
  }

  /** 워크플로가 앞 단계로 되돌아가는 경우를 막기 위해 더 진행된 상태로만 갱신한다. */
  public void advanceTo(SpecStatus next) {
    if (next.ordinal() > status.ordinal()) {
      this.status = next;
    }
  }
}
