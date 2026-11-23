package com.inzent.airestclient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 생성된 RestClient 소스 일체. 다시 생성하면 이력이 쌓이고 최신 건을 사용한다. */
@Entity
@Table(name = "generated_client")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeneratedClient {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "spec_id", nullable = false)
  private Long specId;

  /** 패키지명, 파일 목록, 경고, 컴파일 진단을 담은 JSON(ClientPayload) */
  @Lob
  @Column(name = "files_json", nullable = false)
  private String filesJson;

  @Column(nullable = false)
  private boolean compiled;

  @Lob
  @Column(name = "compile_log")
  private String compileLog;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public GeneratedClient(Long specId, String filesJson, boolean compiled, String compileLog) {
    this.specId = specId;
    this.filesJson = filesJson;
    this.compiled = compiled;
    this.compileLog = compileLog;
    this.createdAt = LocalDateTime.now();
  }
}
