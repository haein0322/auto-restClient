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

/** 엔드포인트별 샘플 요청. (specId, operationId)당 한 건이며 재생성·편집 시 갱신된다. */
@Entity
@Table(name = "sample_data")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SampleData {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "spec_id", nullable = false)
  private Long specId;

  @Column(name = "operation_id", nullable = false)
  private String operationId;

  @Lob
  @Column(name = "request_json")
  private String requestJson;

  @Column(nullable = false)
  private boolean valid;

  /** 검증 오류 목록(JSON 배열 문자열) */
  @Lob
  @Column(name = "validation_log")
  private String validationLog;

  @Column(nullable = false)
  private String source;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public SampleData(Long specId, String operationId, String requestJson, boolean valid,
      String validationLog, String source) {
    this.specId = specId;
    this.operationId = operationId;
    update(requestJson, valid, validationLog, source);
  }

  public void update(String requestJson, boolean valid, String validationLog, String source) {
    this.requestJson = requestJson;
    this.valid = valid;
    this.validationLog = validationLog;
    this.source = source;
    this.updatedAt = LocalDateTime.now();
  }
}
