import { Lock, LockOpen } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { EndpointInfo } from "@/lib/schemas";
import { MethodBadge } from "./MethodBadge";

/** 파싱된 엔드포인트 목록 */
export function EndpointTable({ endpoints }: { endpoints: EndpointInfo[] }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
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
          {endpoints.map((ep) => (
            <TableRow key={ep.operationId}>
              <TableCell>
                <MethodBadge method={ep.method} />
              </TableCell>
              <TableCell className="font-mono text-xs">{ep.path}</TableCell>
              <TableCell className="font-mono text-xs">{ep.operationId}</TableCell>
              <TableCell className="min-w-40">{ep.summary ?? "-"}</TableCell>
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
                    {ep.parameters.length > 0 ? " + " : ""}본문{ep.requestBodyRequired ? "*" : ""}
                  </span>
                )}
                {ep.parameters.length === 0 && !ep.hasRequestBody && "-"}
              </TableCell>
              <TableCell>
                <div className="flex flex-wrap gap-1">
                  {ep.responseCodes.map((code) => (
                    <Badge key={code} variant="outline" className="font-mono">
                      {code}
                    </Badge>
                  ))}
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
