import Link from "next/link";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { STEPS, stepPath, type StepId } from "@/lib/workflow";

interface StepFooterProps {
  current: StepId;
  specId: number;
  /** 다음 단계로 갈 수 있을 때만 넘긴다. 없으면 "다음" 버튼을 숨긴다. */
  nextHref?: string;
}

/**
 * 화면 하단 이동 버튼 줄. 왼쪽은 "이전", 오른쪽은 "다음"으로 모든 단계에서 통일한다.
 * "이전"은 단순 이동이라 현재 화면에서 편집 중이던 내용은 저장하지 않고 버린다.
 * 1단계 결과 화면의 "이전"은 홈(업로드 시작 화면)으로 돌아간다.
 */
export function StepFooter({ current, specId, nextHref }: StepFooterProps) {
  const index = STEPS.findIndex((step) => step.id === current);
  const previous = index > 0 ? STEPS[index - 1] : null;
  const backHref = previous ? stepPath(previous.id, specId) : "/";

  return (
    <div className="flex items-center justify-between gap-3">
      <Link
        href={backHref}
        className={cn(buttonVariants({ variant: "outline", size: "lg" }), "border-foreground")}
        data-testid="back-button"
      >
        <ArrowLeft /> 이전
      </Link>
      {nextHref && (
        <Link href={nextHref} className={buttonVariants({ size: "lg" })} data-testid="next-button">
          다음 <ArrowRight />
        </Link>
      )}
    </div>
  );
}
