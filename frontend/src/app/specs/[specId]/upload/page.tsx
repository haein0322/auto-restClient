"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EndpointTable } from "@/components/workflow/EndpointTable";
import { SpecSummaryCard } from "@/components/workflow/SpecSummaryCard";
import { api } from "@/lib/api";
import { stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/** 1단계 결과: 파싱된 명세 요약과 엔드포인트 목록을 확인하고 다음 단계로 진행한다. */
export default function UploadResultPage() {
  const detail = useWorkflowStore((state) => state.detail);
  const specId = detail?.summary.id;
  const [samples, setSamples] = useState<Record<string, unknown> | undefined>(undefined);

  // 엔드포인트별 미리보기 샘플을 불러온다. 실패해도 목록은 그대로 보여준다(화살표만 없음).
  useEffect(() => {
    if (specId === undefined) {
      return;
    }
    let cancelled = false;
    api
      .previewSamples(specId)
      .then((preview) => {
        if (!cancelled) {
          setSamples(Object.fromEntries(preview.samples.map((s) => [s.operationId, s.request])));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setSamples(undefined);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [specId]);

  if (!detail) {
    return null;
  }

  return (
    <>
      <SpecSummaryCard detail={detail} />

      {detail.warnings.length > 0 && (
        <Alert>
          <AlertTitle>파싱 경고 {detail.warnings.length}건</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {detail.warnings.map((warning) => (
                <li key={warning} className="break-words">
                  {warning}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>엔드포인트 {detail.endpoints.length}개</CardTitle>
          <CardDescription>
            이 목록을 기준으로 AI 분석과 RestClient 코드 생성이 진행됩니다. 왼쪽 화살표를 누르면 샘플 데이터를 볼 수 있습니다.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <EndpointTable endpoints={detail.endpoints} samples={samples} />
        </CardContent>
      </Card>

      <div className="flex justify-end">
        <Link href={stepPath("analysis", detail.summary.id)} className={buttonVariants({ size: "lg" })}>
          다음: AI 명세 분석 <ArrowRight />
        </Link>
      </div>
    </>
  );
}
