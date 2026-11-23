package com.inzent.airestclient.ai;

import com.inzent.airestclient.ai.AiClient.FailureInfo;
import com.inzent.airestclient.dto.TestDtos.CaseDiagnosis;

/** 상태 코드와 실패 메시지로 원인을 추정하는 규칙 기반 진단. Fake AI이자, AI가 누락·실패했을 때의 대체 수단이다. */
public final class RuleBasedDiagnosis {

  private RuleBasedDiagnosis() {}

  public static CaseDiagnosis diagnose(FailureInfo failure) {
    String reason = failure.failureReason() == null ? "" : failure.failureReason();
    Integer status = failure.statusCode();
    String cause;
    String suggestion;

    if (reason.contains("연결할 수 없습니다")) {
      cause = "대상 서버에 연결하지 못했습니다.";
      suggestion = "Base URL의 주소·포트가 맞는지, 서버가 실행 중인지 확인하세요.";
    } else if (reason.contains("시간이 초과")) {
      cause = "서버가 제한 시간 안에 응답하지 않았습니다.";
      suggestion = "서버 상태를 확인하거나 타임아웃을 늘려 다시 실행하세요.";
    } else if (reason.contains("명세와 다른 응답 형식") || reason.contains("스키마와 다릅니다") || reason.contains("본문이 비어")) {
      cause = "서버 응답이 명세(응답 스키마)와 일치하지 않습니다.";
      suggestion = "실제 응답과 명세 중 어느 쪽이 맞는지 확인해 서버 구현 또는 Swagger 명세를 수정하세요.";
    } else if (reason.contains("선언되지 않은 상태 코드")) {
      cause = "서버가 명세에 정의되지 않은 상태 코드로 응답했습니다.";
      suggestion = "명세의 responses에 해당 상태 코드를 추가하거나 서버 응답 코드를 명세에 맞추세요.";
    } else if (status == null) {
      cause = "HTTP 응답을 받기 전에 오류가 발생했습니다.";
      suggestion = "실패 사유 메시지를 확인하세요.";
    } else if (status == 401 || status == 403) {
      cause = "인증 또는 권한 오류입니다. API 키가 없거나 올바르지 않을 수 있습니다.";
      suggestion = "옵션에 올바른 API 키를 입력하세요.";
    } else if (status == 404) {
      cause = "요청한 리소스가 존재하지 않습니다. 샘플의 ID 값이 서버에 없거나 선행 데이터 생성이 실패했을 수 있습니다.";
      suggestion = "선행 생성 호출이 성공했는지 확인하고, 경로 파라미터 샘플 값을 실제 존재하는 ID로 바꾸세요.";
    } else if (status == 409) {
      cause = "리소스 상태 충돌입니다(중복 값, 재고 부족, 이미 처리된 상태 등).";
      suggestion = "응답 메시지를 확인해 중복 불가 값을 바꾸거나 선행 상태를 맞춘 뒤 다시 실행하세요.";
    } else if (status == 400 || status == 422) {
      cause = "서버가 요청 값을 거부했습니다. 샘플이 서버의 검증 규칙을 만족하지 않을 수 있습니다.";
      suggestion = "응답 메시지에 나온 필드를 확인해 4단계에서 샘플 값을 수정하세요.";
    } else if (status >= 500) {
      cause = "서버 내부 오류입니다.";
      suggestion = "서버 로그를 확인하세요. 같은 요청이 반복해서 실패하면 서버 측 수정이 필요합니다.";
    } else {
      cause = "예상하지 못한 응답입니다(HTTP " + status + ").";
      suggestion = "실패 사유와 응답 본문을 확인하세요.";
    }
    return new CaseDiagnosis(failure.operationId(), cause, suggestion);
  }
}
