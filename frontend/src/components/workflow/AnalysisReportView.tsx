import { ArrowRight, ShieldAlert } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AnalysisView, EndpointInfo } from "@/lib/schemas";
import { MethodBadge } from "./MethodBadge";
import { RiskBadge } from "./RiskBadge";

interface AnalysisReportViewProps {
  view: AnalysisView;
  /** operationId → 엔드포인트(메서드/경로 표시용) */
  endpoints: EndpointInfo[];
}

/** 2단계 결과: AI가 분석하고 서버가 검증한 보고서 */
export function AnalysisReportView({ view, endpoints }: AnalysisReportViewProps) {
  const { report } = view;
  const byId = new Map(endpoints.map((ep) => [ep.operationId, ep]));
  const isFake = report.provider === "fake";

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>분석 요약</CardTitle>
            <Badge variant={isFake ? "outline" : "default"}>
              {isFake ? "규칙 기반 분석(Fake)" : `AI: ${report.model}`}
            </Badge>
          </div>
          <CardDescription>{new Date(view.analyzedAt).toLocaleString("ko-KR")} 분석</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          <p>{report.summary}</p>
          {isFake && (
            <p className="text-muted-foreground">
              AI API 키 없이 동작하는 규칙 기반 결과입니다. 실제 Claude 분석을 쓰려면 백엔드를
              <code className="mx-1 rounded bg-muted px-1">ANTHROPIC_API_KEY</code>와
              <code className="mx-1 rounded bg-muted px-1">APP_AI_PROVIDER=claude</code>로 실행하세요.
            </p>
          )}
          <div className="rounded-lg border p-3">
            <p className="mb-1 text-xs text-muted-foreground">
              인증 {report.authentication.required ? "필요" : "불필요"}
              {report.authentication.scheme ? ` · ${report.authentication.scheme}` : ""}
            </p>
            <p>{report.authentication.description}</p>
          </div>
        </CardContent>
      </Card>

      {report.cautions.length > 0 && (
        <Alert>
          <ShieldAlert className="size-4" />
          <AlertTitle>통신 테스트 시 주의사항</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {report.cautions.map((caution) => (
                <li key={caution}>{caution}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>호출 순서와 의존관계</CardTitle>
          <CardDescription>
            의존관계를 만족하도록 서버가 계산한 통신 테스트 실행 순서입니다(생성 → 조회 → 수정 → 삭제).
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <ol className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {report.testOrder.map((operationId, index) => {
              const ep = byId.get(operationId);
              return (
                <li key={operationId} className="flex items-center gap-2 rounded-md border p-2 text-sm">
                  <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold">
                    {index + 1}
                  </span>
                  {ep && <MethodBadge method={ep.method} />}
                  <span className="min-w-0 truncate font-mono text-xs" title={operationId}>
                    {operationId}
                  </span>
                </li>
              );
            })}
          </ol>
          {report.dependencies.length > 0 && (
            <ul className="flex flex-col gap-2 text-sm">
              {report.dependencies.map((dep) => (
                <li
                  key={`${dep.fromOperationId}-${dep.toOperationId}`}
                  className="flex flex-wrap items-center gap-2"
                >
                  <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{dep.fromOperationId}</code>
                  <ArrowRight className="size-3.5 text-muted-foreground" />
                  <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{dep.toOperationId}</code>
                  {dep.reason && <span className="text-muted-foreground">{dep.reason}</span>}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>엔드포인트 분석</CardTitle>
          <CardDescription>제안된 메서드명은 3단계 RestClient 코드 생성에 사용됩니다.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>엔드포인트</TableHead>
                  <TableHead>메서드명</TableHead>
                  <TableHead>목적</TableHead>
                  <TableHead>위험도</TableHead>
                  <TableHead>주의</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {report.endpoints.map((insight) => {
                  const ep = byId.get(insight.operationId);
                  return (
                    <TableRow key={insight.operationId}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          {ep && <MethodBadge method={ep.method} />}
                          <span className="font-mono text-xs">{ep?.path ?? insight.operationId}</span>
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs">{insight.suggestedMethodName}</TableCell>
                      <TableCell className="min-w-48">{insight.purpose}</TableCell>
                      <TableCell>
                        <RiskBadge risk={insight.risk} />
                      </TableCell>
                      <TableCell className="min-w-40 text-xs text-muted-foreground">{insight.notes || "-"}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>

      {report.warnings.length > 0 && (
        <Alert>
          <AlertTitle>검증 중 보정한 내용 {report.warnings.length}건</AlertTitle>
          <AlertDescription>
            <p className="mb-1">AI 응답에서 명세와 맞지 않는 부분을 서버가 자동으로 바로잡았습니다.</p>
            <ul className="list-disc pl-4">
              {report.warnings.map((warning) => (
                <li key={warning} className="break-words">
                  {warning}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}
