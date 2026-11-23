"use client";

import { notFound, useParams } from "next/navigation";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { STEPS, isStepUnlocked } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/**
 * 아직 개별 화면이 구현되지 않은 단계(2~5)의 임시 페이지.
 * 단계별 정적 라우트(analysis, client 등)가 추가되면 그 라우트가 우선 적용된다.
 */
export default function PendingStepPage() {
  const { step: stepId } = useParams<{ step: string }>();
  const status = useWorkflowStore((state) => state.detail?.summary.status);

  const step = STEPS.find((item) => item.id === stepId);
  if (!step) {
    notFound();
  }
  const unlocked = isStepUnlocked(step.order, status);

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {step.order}단계 · {step.label}
        </CardTitle>
        <CardDescription>{step.description}</CardDescription>
      </CardHeader>
      <CardContent className="text-sm text-muted-foreground">
        {unlocked ? "이 단계 화면은 다음 마일스톤에서 구현됩니다." : "이전 단계를 먼저 완료해야 진행할 수 있습니다."}
      </CardContent>
    </Card>
  );
}
