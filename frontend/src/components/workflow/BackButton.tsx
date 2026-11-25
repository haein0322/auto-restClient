import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { STEPS, stepPath, type StepId } from "@/lib/workflow";

/**
 * 이전 단계로 돌아가는 버튼. 단순 이동이라 현재 화면에서 편집 중이던 내용은 저장하지 않고 버린다.
 * 1단계 결과 화면에서는 홈(업로드 시작 화면)으로 돌아간다.
 */
export function BackButton({ current, specId }: { current: StepId; specId: number }) {
  const index = STEPS.findIndex((step) => step.id === current);
  const previous = index > 0 ? STEPS[index - 1] : null;
  const href = previous ? stepPath(previous.id, specId) : "/";
  const label = previous ? `이전: ${previous.label}` : "처음으로";

  return (
    <div>
      <Link href={href} className={buttonVariants({ variant: "outline" })} data-testid="back-button">
        <ArrowLeft /> {label}
      </Link>
    </div>
  );
}
