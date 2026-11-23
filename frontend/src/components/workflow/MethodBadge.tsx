import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const METHOD_STYLES: Record<string, string> = {
  GET: "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300",
  POST: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300",
  PUT: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300",
  PATCH: "bg-purple-100 text-purple-800 dark:bg-purple-950 dark:text-purple-300",
  DELETE: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300",
};

/** HTTP 메서드별 색상 배지 */
export function MethodBadge({ method }: { method: string }) {
  return (
    <Badge
      variant="secondary"
      className={cn("w-16 justify-center font-mono", METHOD_STYLES[method] ?? "")}
    >
      {method}
    </Badge>
  );
}
