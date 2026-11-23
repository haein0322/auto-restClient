"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, ApiError } from "@/lib/api";
import type { SpecSummary } from "@/lib/schemas";
import { stepPath, STEPS, completedSteps } from "@/lib/workflow";
import { ErrorAlert } from "./ErrorAlert";

/** 작업 이력: 이전에 등록한 명세로 돌아가 이어서 작업할 수 있다. */
export function SpecHistory() {
  const [specs, setSpecs] = useState<SpecSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listSpecs()
      .then((list) => !cancelled && setSpecs(list))
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "작업 이력을 불러오지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Card>
      <CardHeader>
        <CardTitle>작업 이력</CardTitle>
        <CardDescription>이전에 등록한 명세를 선택하면 진행 중이던 단계에서 이어갑니다.</CardDescription>
      </CardHeader>
      <CardContent>
        {error && <ErrorAlert message={error} />}
        {!error && !specs && <p className="text-sm text-muted-foreground">불러오는 중...</p>}
        {specs && specs.length === 0 && (
          <p className="text-sm text-muted-foreground">아직 등록된 명세가 없습니다.</p>
        )}
        {specs && specs.length > 0 && (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>명세</TableHead>
                  <TableHead className="text-right">엔드포인트</TableHead>
                  <TableHead>진행 단계</TableHead>
                  <TableHead>등록 일시</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {specs.map((spec) => {
                  const done = completedSteps(spec.status);
                  const resume = STEPS[Math.min(done, STEPS.length - 1)];
                  return (
                    <TableRow key={spec.id}>
                      <TableCell>
                        <Link
                          href={stepPath(resume.id, spec.id)}
                          className="font-medium underline-offset-4 hover:underline"
                        >
                          {spec.title}
                        </Link>
                        {spec.version && <span className="ml-2 text-xs text-muted-foreground">v{spec.version}</span>}
                      </TableCell>
                      <TableCell className="text-right">{spec.endpointCount}</TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {done}/{STEPS.length} 단계 완료
                        </Badge>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">
                        {new Date(spec.createdAt).toLocaleString("ko-KR")}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
