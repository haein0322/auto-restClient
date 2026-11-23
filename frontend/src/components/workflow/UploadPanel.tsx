"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError } from "@/lib/api";
import type { SpecSummary } from "@/lib/schemas";
import { cn } from "@/lib/utils";
import { ErrorAlert } from "./ErrorAlert";
import { FileDropzone } from "./FileDropzone";
import { SampleList } from "./SampleList";
import { UrlImportForm } from "./UrlImportForm";

type Mode = "file" | "url" | "sample";

const MODES: { id: Mode; label: string }[] = [
  { id: "file", label: "파일 업로드" },
  { id: "url", label: "URL 입력" },
  { id: "sample", label: "샘플 불러오기" },
];

/** 1단계: 파일/URL/샘플 중 하나로 Swagger 명세를 등록하고, 성공하면 결과 화면으로 이동한다. */
export function UploadPanel() {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("sample");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(task: () => Promise<SpecSummary>) {
    setPending(true);
    setError(null);
    try {
      const spec = await task();
      router.push(`/specs/${spec.id}/upload`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "등록 중 알 수 없는 오류가 발생했습니다.");
      setPending(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Swagger 명세 등록</CardTitle>
        <CardDescription>
          파일 업로드, URL 입력, 내장 샘플 중 하나로 시작하세요. 등록 즉시 명세를 파싱해 검증합니다.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div role="tablist" aria-label="등록 방식" className="inline-flex w-full rounded-lg bg-muted p-1 sm:w-auto">
          {MODES.map((item) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={mode === item.id}
              onClick={() => {
                setMode(item.id);
                setError(null);
              }}
              disabled={pending}
              className={cn(
                "flex-1 rounded-md px-4 py-1.5 text-sm font-medium transition-colors sm:flex-none",
                mode === item.id ? "bg-background shadow-sm" : "text-muted-foreground hover:text-foreground",
              )}
            >
              {item.label}
            </button>
          ))}
        </div>

        {error && <ErrorAlert title="명세를 등록하지 못했습니다" message={error} />}

        {mode === "file" && (
          <FileDropzone
            disabled={pending}
            onFileSelected={(file) => run(() => api.uploadFile(file))}
            onInvalidFile={setError}
          />
        )}
        {mode === "url" && <UrlImportForm disabled={pending} onSubmitUrl={(url) => run(() => api.importFromUrl(url))} />}
        {mode === "sample" && <SampleList disabled={pending} onSelect={(name) => run(() => api.loadSample(name))} />}

        {pending && (
          <Button variant="ghost" disabled className="self-start">
            <Loader2 className="animate-spin" /> 명세를 파싱하는 중...
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
