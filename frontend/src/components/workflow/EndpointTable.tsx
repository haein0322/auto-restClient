"use client";

import { Fragment, useState } from "react";
import { ChevronDown, ChevronRight, Lock, LockOpen } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { EndpointInfo } from "@/lib/schemas";
import { MethodBadge } from "./MethodBadge";

interface EndpointTableProps {
  endpoints: EndpointInfo[];
  /** operationId별 미리보기 샘플. 있으면 행 왼쪽 화살표로 펼쳐 볼 수 있다. */
  samples?: Record<string, unknown>;
}

/** 파싱된 엔드포인트 목록. samples를 넘기면 행을 펼쳐 샘플 요청 데이터를 보여준다. */
export function EndpointTable({ endpoints, samples }: EndpointTableProps) {
  const [openIds, setOpenIds] = useState<ReadonlySet<string>>(new Set());
  const expandable = samples !== undefined;

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
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            {expandable && <TableHead className="w-8" />}
            <TableHead>메서드</TableHead>
            <TableHead>경로</TableHead>
            <TableHead>operationId</TableHead>
            <TableHead>설명</TableHead>
            <TableHead>인증</TableHead>
            <TableHead>파라미터</TableHead>
            <TableHead>응답 코드</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {endpoints.map((ep) => {
            const open = openIds.has(ep.operationId);
            return (
              <Fragment key={ep.operationId}>
                <TableRow data-testid={`endpoint-row-${ep.operationId}`}>
                  {expandable && (
                    <TableCell>
                      <button
                        type="button"
                        aria-expanded={open}
                        aria-label={`${ep.operationId} 샘플 데이터 ${open ? "접기" : "펼치기"}`}
                        data-testid={`endpoint-toggle-${ep.operationId}`}
                        onClick={() => toggle(ep.operationId)}
                      >
                        {open ? (
                          <ChevronDown className="size-4" />
                        ) : (
                          <ChevronRight className="size-4" />
                        )}
                      </button>
                    </TableCell>
                  )}
                  <TableCell>
                    <MethodBadge method={ep.method} />
                  </TableCell>
                  <TableCell className="font-mono text-xs">{ep.path}</TableCell>
                  <TableCell className="font-mono text-xs">
                    {ep.operationId}
                  </TableCell>
                  <TableCell className="min-w-40">
                    {ep.summary ?? "-"}
                  </TableCell>
                  <TableCell>
                    {ep.secured ? (
                      <span className="inline-flex items-center gap-1 text-xs">
                        <Lock className="size-3.5" /> 필요
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                        <LockOpen className="size-3.5" /> 공개
                      </span>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {ep.parameters.map((p) => `${p.name}(${p.in})`).join(", ")}
                    {ep.hasRequestBody && (
                      <span className="text-muted-foreground">
                        {ep.parameters.length > 0 ? " + " : ""}본문
                        {ep.requestBodyRequired ? "*" : ""}
                      </span>
                    )}
                    {ep.parameters.length === 0 && !ep.hasRequestBody && "-"}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {ep.responseCodes.map((code) => (
                        <Badge
                          key={code}
                          variant="outline"
                          className="font-mono"
                        >
                          {code}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
                {expandable && open && (
                  <TableRow
                    data-testid={`endpoint-sample-${ep.operationId}`}
                    className="bg-muted/30 hover:bg-muted/30"
                  >
                    <TableCell />
                    <TableCell colSpan={7}>
                      <div className="text-xs font-semibold">
                        샘플 데이터 (요청)
                      </div>
                      <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-background p-2 font-mono text-xs">
                        {samples[ep.operationId] === undefined
                          ? "(샘플을 만들지 못했습니다)"
                          : JSON.stringify(samples[ep.operationId], null, 2)}
                      </pre>
                    </TableCell>
                  </TableRow>
                )}
              </Fragment>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
