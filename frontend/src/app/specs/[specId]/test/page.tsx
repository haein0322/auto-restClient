"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Loader2, Stethoscope } from "lucide-react";
import { toast } from "sonner";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorAlert } from "@/components/workflow/ErrorAlert";
import { TestOptionsForm } from "@/components/workflow/TestOptionsForm";
import { TestResultTable } from "@/components/workflow/TestResultTable";
import { api, ApiError } from "@/lib/api";
import type {
  ProgressEvent,
  TestDefaults,
  TestOptionsFormValues,
  TestRunSummary,
  TestRunView,
} from "@/lib/schemas";
import { isStepUnlocked, stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", { hour12: false });
}

/** 5단계: 통신 테스트. 생성된 클라이언트로 샘플을 실제 호출하고, 결과·응답 검증·실패 원인을 보여준다. */
export default function TestPage() {
  const { specId: specIdParam } = useParams<{ specId: string }>();
  const specId = Number(specIdParam);
  const detail = useWorkflowStore((state) => state.detail);
  const loadSpec = useWorkflowStore((state) => state.loadSpec);

  const [defaults, setDefaults] = useState<TestDefaults | null>(null);
  const [runs, setRuns] = useState<TestRunSummary[]>([]);
  const [run, setRun] = useState<TestRunView | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<ProgressEvent | null>(null);
  const [diagnosing, setDiagnosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const unlocked = detail ? isStepUnlocked(5, detail.summary.status) : false;

  // 기본 입력값과 이전 실행 이력을 불러오고, 가장 최근 결과를 바로 보여준다.
  useEffect(() => {
    if (!unlocked) {
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const [loadedDefaults, history] = await Promise.all([api.getTestDefaults(specId), api.listTestRuns(specId)]);
        if (cancelled) {
          return;
        }
        setDefaults(loadedDefaults);
        setRuns(history);
        if (history.length > 0) {
          const latest = await api.getTestRun(specId, history[0].runId);
          if (!cancelled) {
            setRun(latest);
          }
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "테스트 정보를 불러오지 못했습니다.");
        }
      } finally {
        if (!cancelled) {
          setInitialLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
      abortRef.current?.abort();
    };
  }, [specId, unlocked]);

  const startTest = useCallback(
    async (values: TestOptionsFormValues) => {
      const controller = new AbortController();
      abortRef.current = controller;
      setRunning(true);
      setError(null);
      setProgress({ step: "PREPARE", message: "테스트를 시작합니다...", percent: 0 });
      try {
        const result = await api.runTest(specId, values, setProgress, controller.signal);
        setRun(result);
        setRuns(await api.listTestRuns(specId));
        await loadSpec(specId); // 상태가 TESTED로 바뀐다
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") {
          return;
        }
        setError(e instanceof ApiError ? e.message : "통신 테스트 중 알 수 없는 오류가 발생했습니다.");
      } finally {
        setRunning(false);
      }
    },
    [specId, loadSpec],
  );

  async function selectRun(runId: number) {
    setError(null);
    try {
      setRun(await api.getTestRun(specId, runId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "실행 결과를 불러오지 못했습니다.");
    }
  }

  async function diagnose() {
    if (!run) {
      return;
    }
    setDiagnosing(true);
    try {
      const diagnosis = await api.diagnoseTestRun(specId, run.runId);
      setRun({ ...run, diagnosis });
      toast.success("실패 원인 분석을 마쳤습니다. 실패한 행을 펼쳐 확인하세요.");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "원인 분석에 실패했습니다.");
    } finally {
      setDiagnosing(false);
    }
  }

  if (!detail) {
    return null;
  }

  if (!unlocked) {
    return (
      <Alert data-testid="step-locked">
        <AlertTitle>이전 단계를 먼저 완료해야 합니다</AlertTitle>
        <AlertDescription>
          통신 테스트는 생성된 RestClient와 샘플 데이터로 실행합니다.{" "}
          <Link href={stepPath("samples", specId)} className="font-medium underline underline-offset-4">
            샘플 데이터로 이동
          </Link>
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>API 통신 테스트</CardTitle>
          <CardDescription>
            생성된 RestClient로 샘플을 실제 호출합니다. 호출 순서는 AI 분석 결과를 따르고, 앞 호출에서 얻은 ID를 뒤 호출에 넘깁니다.
            응답은 상태 코드와 명세 스키마로 검증합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {defaults ? (
            <TestOptionsForm defaults={defaults} running={running} onSubmit={startTest} />
          ) : (
            initialLoading && <p className="text-sm text-muted-foreground">입력값을 불러오는 중...</p>
          )}

          {running && progress && (
            <div className="flex flex-col gap-2" role="status" aria-live="polite">
              <div
                className="h-2 w-full overflow-hidden rounded-full bg-muted"
                role="progressbar"
                aria-valuenow={progress.percent}
                aria-valuemin={0}
                aria-valuemax={100}
              >
                <div className="h-full bg-primary transition-all" style={{ width: `${progress.percent}%` }} />
              </div>
              <p className="text-sm text-muted-foreground" data-testid="test-progress">
                {progress.percent}% · {progress.message}
              </p>
            </div>
          )}

          {error && <ErrorAlert title="통신 테스트에 실패했습니다" message={error} />}
        </CardContent>
      </Card>

      {run && (
        <Card>
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              테스트 결과
              <Badge className="bg-green-600 text-white" data-testid="count-passed">
                통과 {run.passed}
              </Badge>
              <Badge variant={run.failed > 0 ? "destructive" : "secondary"} data-testid="count-failed">
                실패 {run.failed}
              </Badge>
              <Badge variant="secondary" data-testid="count-skipped">
                건너뜀 {run.skipped}
              </Badge>
              <Badge variant="outline">전체 {run.total}</Badge>
            </CardTitle>
            <CardDescription>
              {formatTime(run.createdAt)} · <span className="font-mono">{run.baseUrl}</span>
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {run.skipped > 0 && (
              <p className="text-sm text-muted-foreground">
                건너뛴 케이스는 위험 호출이거나 선행 호출이 실패해 실행하지 않은 것입니다. 행을 펼치면 이유가 보입니다.
              </p>
            )}

            {run.failed > 0 && (
              <div className="flex flex-wrap items-center gap-3">
                <Button variant="outline" onClick={diagnose} disabled={diagnosing} data-testid="diagnose">
                  {diagnosing ? <Loader2 className="animate-spin" /> : <Stethoscope />}
                  {run.diagnosis ? "원인 다시 분석" : "실패 원인 분석"}
                </Button>
                {run.diagnosis && (
                  <Badge variant="outline">
                    {run.diagnosis.provider === "fake" || run.diagnosis.provider === "rule-based"
                      ? "규칙 기반"
                      : `AI: ${run.diagnosis.provider}`}
                  </Badge>
                )}
              </div>
            )}

            {run.diagnosis && (
              <Alert data-testid="diagnosis-summary">
                <AlertTitle>원인 분석 요약</AlertTitle>
                <AlertDescription className="break-words">{run.diagnosis.summary}</AlertDescription>
              </Alert>
            )}

            {/* 실행이 바뀌면 펼침 상태를 초기화한다(원인 분석 갱신 시에는 runId가 같아 유지된다). */}
            <TestResultTable
              key={run.runId}
              cases={run.cases} diagnoses={run.diagnosis?.cases ?? []}
              diagnosisProvider={run.diagnosis?.provider}
            />
          </CardContent>
        </Card>
      )}

      {runs.length > 1 && (
        <Card>
          <CardHeader>
            <CardTitle>실행 이력</CardTitle>
            <CardDescription>이전 실행을 선택하면 결과를 다시 볼 수 있습니다.</CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="flex flex-col gap-1 text-sm" data-testid="run-history">
              {runs.map((item) => (
                <li key={item.runId}>
                  <button
                    type="button"
                    onClick={() => selectRun(item.runId)}
                    aria-current={run?.runId === item.runId}
                    className="flex w-full flex-wrap items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-muted aria-[current=true]:bg-muted"
                  >
                    <span>{formatTime(item.createdAt)}</span>
                    <span className="font-mono text-xs text-muted-foreground">{item.baseUrl}</span>
                    <span className="ml-auto text-xs">
                      통과 {item.passed} · 실패 {item.failed} · 건너뜀 {item.skipped}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </>
  );
}
