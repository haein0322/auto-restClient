"use client";

import { useState } from "react";
import { CheckCircle2, Loader2, RefreshCw, Save, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { SampleItem, SampleSource } from "@/lib/schemas";
import { cn } from "@/lib/utils";
import { MethodBadge } from "./MethodBadge";

const SOURCE_LABELS: Record<SampleSource, string> = {
  RULE: "규칙 기반",
  AI: "AI 생성",
  FALLBACK: "AI 대체(규칙)",
  USER: "직접 편집",
};

interface SampleCardProps {
  item: SampleItem;
  onSave: (operationId: string, request: unknown) => Promise<void>;
  onRegenerate: (operationId: string) => Promise<void>;
}

/**
 * 엔드포인트 하나의 샘플 편집 카드. 서버에서 받은 항목이 바뀌면(저장·재생성) 부모가 key로 다시 마운트해
 * 편집 중 텍스트가 항상 최신 서버 값에서 시작한다.
 */
export function SampleCard({ item, onSave, onRegenerate }: SampleCardProps) {
  const original = JSON.stringify(item.request, null, 2);
  const [text, setText] = useState(original);
  const [parseError, setParseError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"save" | "regenerate" | null>(null);

  const dirty = text !== original;

  async function save() {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch (e) {
      setParseError(e instanceof Error ? e.message : "JSON 형식이 올바르지 않습니다.");
      return;
    }
    setParseError(null);
    setBusy("save");
    try {
      await onSave(item.operationId, parsed);
    } finally {
      setBusy(null);
    }
  }

  async function regenerate() {
    setBusy("regenerate");
    try {
      await onRegenerate(item.operationId);
    } finally {
      setBusy(null);
    }
  }

  return (
    <section
      className={cn("rounded-lg border p-4", !item.valid && "border-destructive/50")}
      aria-label={`${item.operationId} 샘플`}
      data-testid={`sample-${item.operationId}`}
    >
      <header className="mb-3 flex flex-wrap items-center gap-2">
        <MethodBadge method={item.method} />
        <span className="font-mono text-xs">{item.path}</span>
        <span className="font-mono text-xs text-muted-foreground">{item.operationId}</span>
        <div className="ml-auto flex items-center gap-2">
          <Badge variant="outline">{SOURCE_LABELS[item.source]}</Badge>
          {item.valid ? (
            <span className="inline-flex items-center gap-1 text-xs text-green-700 dark:text-green-400">
              <CheckCircle2 className="size-3.5" /> 검증 통과
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 text-xs text-destructive">
              <XCircle className="size-3.5" /> 검증 실패
            </span>
          )}
        </div>
      </header>

      <Textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        spellCheck={false}
        aria-label={`${item.operationId} 샘플 JSON`}
        aria-invalid={parseError ? true : undefined}
        className="min-h-24 font-mono text-xs"
        rows={Math.min(14, Math.max(4, text.split("\n").length))}
        disabled={busy !== null}
      />

      {parseError && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          JSON 형식 오류: {parseError}
        </p>
      )}
      {!item.valid && item.errors.length > 0 && !dirty && (
        <ul className="mt-2 list-disc pl-5 text-xs text-destructive" data-testid={`errors-${item.operationId}`}>
          {item.errors.map((error) => (
            <li key={error} className="break-words">
              {error}
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button size="sm" onClick={save} disabled={!dirty || busy !== null} data-testid={`save-${item.operationId}`}>
          {busy === "save" ? <Loader2 className="animate-spin" /> : <Save />}
          저장·검증
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={regenerate}
          disabled={busy !== null}
          data-testid={`regenerate-${item.operationId}`}
        >
          {busy === "regenerate" ? <Loader2 className="animate-spin" /> : <RefreshCw />}
          재생성
        </Button>
        {dirty && <span className="text-xs text-muted-foreground">저장하지 않은 변경이 있습니다.</span>}
      </div>
    </section>
  );
}
