import type { SpecStatus } from "./schemas";

export type StepId = "upload" | "analysis" | "client" | "samples" | "test";

export interface StepDefinition {
  id: StepId;
  order: number;
  label: string;
  description: string;
}

/** 워크플로 5단계 정의 */
export const STEPS: readonly StepDefinition[] = [
  { id: "upload", order: 1, label: "Swagger 업로드", description: "명세 등록 및 파싱" },
  { id: "analysis", order: 2, label: "AI 명세 분석", description: "인증·의존관계 분석" },
  { id: "client", order: 3, label: "RestClient 생성", description: "Java 코드 자동 생성" },
  { id: "samples", order: 4, label: "샘플 데이터", description: "요청 샘플 자동 생성" },
  { id: "test", order: 5, label: "통신 테스트", description: "실제 API 호출 검증" },
];

/** 상태별로 완료된 단계 수 (PARSED → 1단계까지 완료) */
const COMPLETED_STEPS: Record<SpecStatus, number> = {
  PARSED: 1,
  ANALYZED: 2,
  CLIENT_GENERATED: 3,
  SAMPLES_READY: 4,
  TESTED: 5,
};

export function completedSteps(status: SpecStatus | undefined): number {
  return status ? COMPLETED_STEPS[status] : 0;
}

/** 이전 단계를 모두 마친 경우에만 해당 단계로 이동할 수 있다. */
export function isStepUnlocked(order: number, status: SpecStatus | undefined): boolean {
  return order <= completedSteps(status) + 1;
}

export function stepPath(id: StepId, specId: number | undefined): string {
  if (id === "upload" && specId === undefined) {
    return "/";
  }
  return `/specs/${specId}/${id}`;
}
