import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { SpecDetail } from "@/lib/schemas";

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg border p-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 text-lg font-semibold">{value}</dd>
    </div>
  );
}

/** 파싱된 명세의 요약 정보 */
export function SpecSummaryCard({ detail }: { detail: SpecDetail }) {
  const { summary } = detail;
  const sourceLabel: Record<string, string> = { FILE: "파일 업로드", URL: "URL", SAMPLE: "샘플" };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="break-words">{summary.title}</CardTitle>
        {detail.description && (
          <CardDescription className="whitespace-pre-line">{detail.description.trim()}</CardDescription>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label="버전" value={summary.version ?? "-"} />
          <Stat label="엔드포인트" value={summary.endpointCount} />
          <Stat label="스키마" value={summary.schemaCount} />
          <Stat label="등록 방식" value={sourceLabel[summary.sourceType] ?? summary.sourceType} />
        </dl>
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <p className="mb-1 text-xs text-muted-foreground">서버</p>
            {detail.servers.length > 0 ? (
              detail.servers.map((server) => (
                <p key={server} className="break-all font-mono text-xs">
                  {server}
                </p>
              ))
            ) : (
              <p className="text-muted-foreground">정의되지 않음</p>
            )}
          </div>
          <div>
            <p className="mb-1 text-xs text-muted-foreground">인증 방식</p>
            {detail.securitySchemes.length > 0 ? (
              detail.securitySchemes.map((scheme) => (
                <p key={scheme.name} className="text-xs">
                  <span className="font-medium">{scheme.name}</span> · {scheme.type}
                  {scheme.location && ` (${scheme.location}`}
                  {scheme.keyName && `: ${scheme.keyName}`}
                  {scheme.location && ")"}
                </p>
              ))
            ) : (
              <p className="text-muted-foreground">없음</p>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
