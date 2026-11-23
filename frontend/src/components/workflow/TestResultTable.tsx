"use client";

import { Fragment, useState } from "react";
import { CheckCircle2, ChevronDown, ChevronRight, MinusCircle, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { MethodBadge } from "@/components/workflow/MethodBadge";
import type { CaseDiagnosis, TestCase, TestOutcome } from "@/lib/schemas";
import { cn } from "@/lib/utils";

const OUTCOME_STYLES: Record<TestOutcome, { label: string; className: string; icon: typeof CheckCircle2 }> = {
  PASS: {
    label: "통과",
    className: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300",
    icon: CheckCircle2,
  },
  FAIL: {
    label: "실패",
    className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300",
    icon: XCircle,
  },
  SKIPPED: {
    label: "건너뜀",
    className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
    icon: MinusCircle,
  },
};

export function OutcomeBadge({ outcome }: { outcome: TestOutcome }) {
  const style = OUTCOME_STYLES[outcome];
  const Icon = style.icon;
  return (
    <Badge variant="secondary" className={cn("w-20 justify-center gap-1", style.className)}>
      <Icon className="size-3" aria-hidden />
      {style.label}
    </Badge>
  );
}

/** 응답 본문이 JSON이면 보기 좋게 들여쓴다. 아니면 원문 그대로 보여준다. */
function prettyBody(body: string | null): string {
  if (!body) {
    return "(본문 없음)";
  }
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

function statusClass(code: number | null): string {
  if (code === null) {
    return "text-muted-foreground";
  }
  return code >= 400 ? "text-red-600 dark:text-red-400" : "text-green-700 dark:text-green-400";
}

interface TestResultTableProps {
  cases: TestCase[];
  diagnoses: CaseDiagnosis[];
  /** 분석 출처(claude 등). 규칙 기반이면 제목에 AI라고 쓰지 않는다. */
  diagnosisProvider?: string;
}

/** 케이스별 결과 표. 행을 펼치면 실제 전송한 요청, 응답 본문, 실패 사유와 AI 원인 분석이 보인다. */
export function TestResultTable({ cases, diagnoses, diagnosisProvider }: TestResultTableProps) {
  const ruleBased = diagnosisProvider === "fake" || diagnosisProvider === "rule-based";
  const [openIds, setOpenIds] = useState<ReadonlySet<string>>(new Set());

  function toggle(operationId: string) {
    setOpenIds((current) => {
      const next = new Set(current);
      if (next.has(operationId)) {
        next.delete(operationId);
      } else {
        next.add(operationId);
      }
      return next;
    });
  }

  return (
    <Table data-testid="test-result-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-8" />
          <TableHead className="w-24">결과</TableHead>
          <TableHead className="w-20">메서드</TableHead>
          <TableHead>엔드포인트</TableHead>
          <TableHead className="w-20 text-right">상태</TableHead>
          <TableHead className="w-24 text-right">소요(ms)</TableHead>
          <TableHead className="w-24">스키마</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {cases.map((testCase) => {
          const open = openIds.has(testCase.operationId);
          const diagnosis = diagnoses.find((d) => d.operationId === testCase.operationId);
          return (
            <Fragment key={testCase.operationId}>
              <TableRow
                data-testid={`case-${testCase.operationId}`}
                data-outcome={testCase.outcome}
                className="cursor-pointer"
                onClick={() => toggle(testCase.operationId)}
              >
                <TableCell>
                  <button
                    type="button"
                    aria-expanded={open}
                    aria-label={`${testCase.operationId} 상세 ${open ? "접기" : "펼치기"}`}
                    onClick={(event) => {
                      event.stopPropagation();
                      toggle(testCase.operationId);
                    }}
                  >
                    {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                  </button>
                </TableCell>
                <TableCell>
                  <OutcomeBadge outcome={testCase.outcome} />
                </TableCell>
                <TableCell>
                  <MethodBadge method={testCase.method} />
                </TableCell>
                <TableCell>
                  <div className="font-medium">{testCase.operationId}</div>
                  <div className="font-mono text-xs text-muted-foreground">{testCase.path}</div>
                </TableCell>
                <TableCell className={cn("text-right font-mono", statusClass(testCase.statusCode))}>
                  {testCase.statusCode ?? "-"}
                </TableCell>
                <TableCell className="text-right font-mono">{testCase.elapsedMs ?? "-"}</TableCell>
                <TableCell>
                  {testCase.schemaValid === null ? (
                    <span className="text-muted-foreground">-</span>
                  ) : testCase.schemaValid ? (
                    <span className="text-green-700 dark:text-green-400">일치</span>
                  ) : (
                    <span className="text-red-600 dark:text-red-400">불일치</span>
                  )}
                </TableCell>
              </TableRow>

              {open && (
                <TableRow data-testid={`detail-${testCase.operationId}`} className="bg-muted/30 hover:bg-muted/30">
                  <TableCell />
                  <TableCell colSpan={6}>
                    <div className="flex flex-col gap-3 py-2">
                      {testCase.failureReason && (
                        <div>
                          <div className="text-xs font-semibold">
                            {testCase.outcome === "SKIPPED" ? "건너뛴 이유" : "실패 사유"}
                          </div>
                          <p className="text-sm break-words whitespace-pre-wrap">{testCase.failureReason}</p>
                        </div>
                      )}

                      {diagnosis && (
                        <div
                          className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/40"
                          data-testid={`diagnosis-${testCase.operationId}`}
                        >
                          <div className="text-xs font-semibold">{ruleBased ? "원인 분석(규칙 기반)" : "AI 원인 분석"}</div>
                          <p className="mt-1">
                            <span className="font-medium">원인: </span>
                            {diagnosis.likelyCause}
                          </p>
                          <p className="mt-1">
                            <span className="font-medium">해결: </span>
                            {diagnosis.suggestion}
                          </p>
                        </div>
                      )}

                      <div className="grid gap-3 lg:grid-cols-2">
                        <div>
                          <div className="text-xs font-semibold">전송한 요청</div>
                          <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-background p-2 font-mono text-xs">
                            {testCase.request ? JSON.stringify(testCase.request, null, 2) : "(전송하지 않음)"}
                          </pre>
                        </div>
                        <div>
                          <div className="text-xs font-semibold">응답 본문</div>
                          <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-background p-2 font-mono text-xs">
                            {prettyBody(testCase.responseBody)}
                          </pre>
                        </div>
                      </div>
                    </div>
                  </TableCell>
                </TableRow>
              )}
            </Fragment>
          );
        })}
      </TableBody>
    </Table>
  );
}
