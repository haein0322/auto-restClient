package com.inzent.airestclient.domain;

/** 명세의 워크플로 진행 단계 (UI의 단계 잠금 판단에 사용) */
public enum SpecStatus {
  PARSED,
  ANALYZED,
  CLIENT_GENERATED,
  SAMPLES_READY,
  TESTED
}
