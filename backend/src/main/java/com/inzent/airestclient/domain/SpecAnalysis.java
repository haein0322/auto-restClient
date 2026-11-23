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

/** 명세에 대한 AI 분석 결과(검증을 마친 보고서 JSON). 재분석하면 이력이 쌓이고 최신 건을 사용한다. */
@Entity
@Table(name = "analysis_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecAnalysis {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "spec_id", nullable = false)
  private Long specId;

  @Lob
  @Column(name = "result_json", nullable = false)
  private String resultJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  public SpecAnalysis(Long specId, String resultJson) {
    this.specId = specId;
    this.resultJson = resultJson;
    this.createdAt = LocalDateTime.now();
  }
}
