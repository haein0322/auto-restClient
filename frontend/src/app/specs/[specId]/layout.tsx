"use client";

import { useEffect, type ReactNode } from "react";
import { useParams, usePathname } from "next/navigation";
import { Loader2 } from "lucide-react";
import { BackButton } from "@/components/workflow/BackButton";
import { ErrorAlert } from "@/components/workflow/ErrorAlert";
import { StepNav } from "@/components/workflow/StepNav";
import { STEPS, type StepId } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/** 명세별 작업 화면 공통 레이아웃: 명세를 불러와 스토어에 보관하고 단계 내비게이션을 표시한다. */
export default function SpecLayout({ children }: { children: ReactNode }) {
  const params = useParams<{ specId: string }>();
  const pathname = usePathname();
  const { detail, loading, error, loadSpec, reset } = useWorkflowStore();

  const specId = Number(params.specId);
  const lastSegment = pathname.split("/").filter(Boolean).pop();
  const current = STEPS.find((step) => step.id === lastSegment)?.id ?? ("upload" satisfies StepId);

  useEffect(() => {
    if (Number.isInteger(specId) && specId > 0) {
      void loadSpec(specId);
    }
    return () => reset();
  }, [specId, loadSpec, reset]);

  if (!Number.isInteger(specId) || specId <= 0) {
    return <ErrorAlert title="잘못된 주소" message="명세 ID가 올바르지 않습니다." />;
  }

  return (
    <>
      <StepNav current={current} specId={specId} status={detail?.summary.status} />
      <BackButton current={current} specId={specId} />
      {loading && !detail && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" /> 명세를 불러오는 중...
        </p>
      )}
      {error && <ErrorAlert title="명세를 불러오지 못했습니다" message={error} />}
      {detail && children}
    </>
  );
}
