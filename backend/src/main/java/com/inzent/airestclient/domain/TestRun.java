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

/** 통신 테스트 한 번의 실행 기록. 케이스 결과는 {@link TestCaseResult}에 저장된다. */
@Entity
@Table(name = "test_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "spec_id", nullable = false)
  private Long specId;

  @Column(name = "base_url", nullable = false)
  private String baseUrl;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private int total;

  @Column(nullable = false)
  private int passed;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** AI 원인 분석 결과(JSON). 분석하기 전에는 null. */
  @Lob
  @Column(name = "diagnosis_json")
  private String diagnosisJson;

  public TestRun(Long specId, String baseUrl, int total, int passed) {
    this.specId = specId;
    this.baseUrl = baseUrl;
    this.status = "COMPLETED";
    this.total = total;
    this.passed = passed;
    this.createdAt = LocalDateTime.now();
  }

  public void saveDiagnosis(String diagnosisJson) {
    this.diagnosisJson = diagnosisJson;
  }
}
