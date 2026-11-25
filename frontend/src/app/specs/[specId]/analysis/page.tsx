"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { Loader2, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { AnalysisReportView } from "@/components/workflow/AnalysisReportView";
import { StepFooter } from "@/components/workflow/StepFooter";
import { ErrorAlert } from "@/components/workflow/ErrorAlert";
import { api, ApiError } from "@/lib/api";
import type { AnalysisView, ProgressEvent } from "@/lib/schemas";
import { stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/** 2단계: AI 명세 분석. 시작 버튼 → SSE 진행률 → 검증된 분석 보고서 표시. */
export default function AnalysisPage() {
  const { specId: specIdParam } = useParams<{ specId: string }>();
  const specId = Number(specIdParam);
  const detail = useWorkflowStore((state) => state.detail);
  const loadSpec = useWorkflowStore((state) => state.loadSpec);

  const [view, setView] = useState<AnalysisView | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<ProgressEvent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 이미 분석한 결과가 있으면 바로 보여준다.
  useEffect(() => {
    let cancelled = false;
    api
      .getAnalysis(specId)
      .then((existing) => !cancelled && setView(existing))
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "분석 결과를 불러오지 못했습니다.");
        }
      })
      .finally(() => !cancelled && setInitialLoading(false));
    return () => {
      cancelled = true;
      abortRef.current?.abort();
    };
  }, [specId]);

  const startAnalysis = useCallback(async () => {
    const controller = new AbortController();
    abortRef.current = controller;
    setRunning(true);
    setError(null);
    setProgress({
      step: "PREPARE",
      message: "분석을 시작합니다...",
      percent: 0,
    });
    try {
      const result = await api.runAnalysis(specId, setProgress, controller.signal);
      setView(result);
      await loadSpec(specId); // 상태가 ANALYZED로 바뀌어 3단계가 열린다
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") {
        return;
      }
      setError(e instanceof ApiError ? e.message : "분석 중 알 수 없는 오류가 발생했습니다.");
    } finally {
      setRunning(false);
    }
  }, [specId, loadSpec]);

  if (!detail) {
    return null;
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>AI 명세 분석</CardTitle>
          <CardDescription>
            엔드포인트의 목적·위험도·호출 순서·인증 방식을 분석합니다. 결과는 서버가 명세와 대조해 검증한 뒤 저장합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={startAnalysis} disabled={running || initialLoading} data-testid="start-analysis">
              {running ? <Loader2 className="animate-spin" /> : <Sparkles />}
              {view ? "다시 분석" : "분석 시작"}
            </Button>
            <span className="text-sm text-muted-foreground">엔드포인트 {detail.summary.endpointCount}개 대상</span>
          </div>

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
              <p className="text-sm text-muted-foreground" data-testid="analysis-progress">
                {progress.percent}% · {progress.message}
              </p>
            </div>
          )}

          {error && <ErrorAlert title="분석에 실패했습니다" message={error} />}
        </CardContent>
      </Card>

      {view && <AnalysisReportView view={view} endpoints={detail.endpoints} />}

      <StepFooter current="analysis" specId={specId} nextHref={view ? stepPath("client", specId) : undefined} />
    </>
  );
}
