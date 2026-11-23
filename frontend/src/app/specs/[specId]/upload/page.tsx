"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EndpointTable } from "@/components/workflow/EndpointTable";
import { SpecSummaryCard } from "@/components/workflow/SpecSummaryCard";
import { stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

/** 1단계 결과: 파싱된 명세 요약과 엔드포인트 목록을 확인하고 다음 단계로 진행한다. */
export default function UploadResultPage() {
  const detail = useWorkflowStore((state) => state.detail);
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
          <CardDescription>이 목록을 기준으로 AI 분석과 RestClient 코드 생성이 진행됩니다.</CardDescription>
        </CardHeader>
        <CardContent>
          <EndpointTable endpoints={detail.endpoints} />
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
