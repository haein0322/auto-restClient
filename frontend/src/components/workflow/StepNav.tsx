import Link from "next/link";
import { Check, Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import type { SpecStatus } from "@/lib/schemas";
import { STEPS, completedSteps, isStepUnlocked, stepPath, type StepId } from "@/lib/workflow";

interface StepNavProps {
  current: StepId;
  specId?: number;
  status?: SpecStatus;
}

/** 상단 5단계 인디케이터. 이전 단계를 마치지 않은 단계는 잠금 처리한다. */
export function StepNav({ current, specId, status }: StepNavProps) {
  const done = completedSteps(status);

  return (
    <nav aria-label="진행 단계" className="w-full">
      <ol className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        {STEPS.map((step) => {
          const unlocked = specId !== undefined ? isStepUnlocked(step.order, status) : step.order === 1;
          const completed = step.order <= done;
          const active = step.id === current;

          const content = (
            <div
              className={cn(
                "flex h-full items-center gap-3 rounded-lg border p-3 transition-colors",
                active && "border-primary bg-primary/5",
                !active && unlocked && "hover:bg-muted",
                !unlocked && "cursor-not-allowed opacity-50",
              )}
            >
              <span
                className={cn(
                  "flex size-8 shrink-0 items-center justify-center rounded-full border text-sm font-semibold",
                  completed && "border-primary bg-primary text-primary-foreground",
                  active && !completed && "border-primary text-primary",
                )}
              >
                {completed ? <Check className="size-4" /> : unlocked ? step.order : <Lock className="size-3.5" />}
              </span>
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium">{step.label}</span>
                <span className="hidden truncate text-xs text-muted-foreground lg:block">
                  {step.description}
                </span>
              </span>
            </div>
          );

          return (
            <li key={step.id} aria-current={active ? "step" : undefined}>
              {unlocked ? (
                <Link href={stepPath(step.id, specId)}>{content}</Link>
              ) : (
                <div aria-disabled="true">{content}</div>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
