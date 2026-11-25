"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Loader2, Wand2 } from "lucide-react";
import { toast } from "sonner";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { StepFooter } from "@/components/workflow/StepFooter";
import { ErrorAlert } from "@/components/workflow/ErrorAlert";
import { SampleCard } from "@/components/workflow/SampleCard";
import { api, ApiError } from "@/lib/api";
import type { SampleItem, SampleSetView } from "@/lib/schemas";
import { isStepUnlocked, stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/** 4단계: 샘플 데이터. 엔드포인트별 요청 샘플을 생성하고, 서버 검증 결과를 보며 직접 편집·재생성한다. */
export default function SamplesPage() {
  const { specId: specIdParam } = useParams<{ specId: string }>();
  const specId = Number(specIdParam);
  const detail = useWorkflowStore((state) => state.detail);
  const loadSpec = useWorkflowStore((state) => state.loadSpec);

  const [set, setSet] = useState<SampleSetView | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getSamples(specId)
      .then((existing) => !cancelled && setSet(existing))
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "샘플을 불러오지 못했습니다.");
        }
      })
      .finally(() => !cancelled && setInitialLoading(false));
    return () => {
      cancelled = true;
    };
  }, [specId]);

  /** 항목 하나가 갱신되면 목록과 유효 개수를 로컬에서 함께 갱신한다(전체 재조회 없이). */
  const replaceItem = useCallback((updated: SampleItem) => {
    setSet((current) => {
      if (!current) {
        return current;
      }
      const samples = current.samples.map((s) => (s.operationId === updated.operationId ? updated : s));
      return {
        ...current,
        samples,
        validCount: samples.filter((s) => s.valid).length,
      };
    });
  }, []);

  async function generate() {
    setGenerating(true);
    setError(null);
    try {
      setSet(await api.generateSamples(specId));
      await loadSpec(specId); // 상태가 SAMPLES_READY로 바뀌어 5단계가 열린다
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "샘플 생성 중 알 수 없는 오류가 발생했습니다.");
    } finally {
      setGenerating(false);
    }
  }

  const save = useCallback(
    async (operationId: string, request: unknown) => {
      try {
        const updated = await api.updateSample(specId, operationId, request);
        replaceItem(updated);
        if (updated.valid) {
          toast.success(`${operationId} 샘플을 저장했습니다.`);
          await loadSpec(specId);
        } else {
          toast.error(`${operationId} 샘플이 검증을 통과하지 못했습니다. 오류를 확인하세요.`);
        }
      } catch (e) {
        toast.error(e instanceof ApiError ? e.message : "저장에 실패했습니다.");
      }
    },
    [specId, replaceItem, loadSpec],
  );

  const regenerate = useCallback(
    async (operationId: string) => {
      try {
        const updated = await api.regenerateSample(specId, operationId);
        replaceItem(updated);
        toast.success(`${operationId} 샘플을 다시 생성했습니다.`);
      } catch (e) {
        toast.error(e instanceof ApiError ? e.message : "재생성에 실패했습니다.");
      }
    },
    [specId, replaceItem],
  );

  if (!detail) {
    return null;
  }

  if (!isStepUnlocked(4, detail.summary.status)) {
    return (
      <>
        <Alert data-testid="step-locked">
          <AlertTitle>이전 단계를 먼저 완료해야 합니다</AlertTitle>
          <AlertDescription>
            샘플 데이터는 생성된 RestClient 기준으로 만듭니다.{" "}
            <Link href={stepPath("client", specId)} className="font-medium underline underline-offset-4">
              RestClient 생성으로 이동
            </Link>
          </AlertDescription>
        </Alert>
        <StepFooter current="samples" specId={specId} />
      </>
    );
  }

  const hasValid = (set?.validCount ?? 0) > 0;

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>샘플 데이터 자동 생성</CardTitle>
          <CardDescription>
            엔드포인트별 요청 샘플({"{pathParams, queryParams, headers, body}"}
            )을 만들고, 명세(필수값·enum·범위·형식)로 검증합니다. AI가 만든 값이 검증을 통과하지 못하면 오류를 알려 다시
            생성하고, 끝내 실패하면 규칙 기반으로 대체합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={generate} disabled={generating || initialLoading} data-testid="generate-samples">
              {generating ? <Loader2 className="animate-spin" /> : <Wand2 />}
              {set ? "전체 다시 생성" : "샘플 생성"}
            </Button>
            {set && (
              <>
                <Badge variant={set.validCount === set.total ? "default" : "destructive"} data-testid="valid-count">
                  유효 {set.validCount} / 전체 {set.total}
                </Badge>
                <Badge variant="outline">{set.provider === "fake" ? "규칙 기반(Fake)" : `AI: ${set.provider}`}</Badge>
              </>
            )}
          </div>
          {set && set.validCount < set.total && (
            <p className="text-sm text-muted-foreground">
              검증에 실패한 샘플은 통신 테스트에서 제외됩니다. 직접 고치거나 재생성하세요.
            </p>
          )}
          {error && <ErrorAlert title="샘플 생성에 실패했습니다" message={error} />}
        </CardContent>
      </Card>

      {set && set.warnings.length > 0 && (
        <Alert>
          <AlertTitle>생성 중 알림 {set.warnings.length}건</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {set.warnings.map((warning) => (
                <li key={warning} className="break-words">
                  {warning}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {set && (
        <div className="flex flex-col gap-4">
          {set.samples.map((item) => (
            <SampleCard
              key={`${item.operationId}-${item.updatedAt}`}
              item={item}
              onSave={save}
              onRegenerate={regenerate}
            />
          ))}
        </div>
      )}

      <StepFooter current="samples" specId={specId} nextHref={set && hasValid ? stepPath("test", specId) : undefined} />
    </>
  );
}
