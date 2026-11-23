import { Badge } from "@/components/ui/badge";
import type { RiskLevel } from "@/lib/schemas";
import { cn } from "@/lib/utils";

const RISK_STYLES: Record<RiskLevel, { label: string; className: string }> = {
  SAFE: { label: "조회", className: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300" },
  MUTATING: { label: "변경", className: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300" },
  DESTRUCTIVE: { label: "삭제", className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300" },
};

/** 호출 위험도 배지 (통신 테스트 실행 허용 여부의 기준) */
export function RiskBadge({ risk }: { risk: RiskLevel }) {
  const style = RISK_STYLES[risk];
  return (
    <Badge variant="secondary" className={cn("w-12 justify-center", style.className)}>
      {style.label}
    </Badge>
  );
}
