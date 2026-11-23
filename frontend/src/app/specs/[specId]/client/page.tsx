"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowRight, CheckCircle2, Code2, Download, Loader2, XCircle } from "lucide-react";
import { toast } from "sonner";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { CodeTreeViewer } from "@/components/workflow/CodeTreeViewer";
import { ErrorAlert } from "@/components/workflow/ErrorAlert";
import { api, ApiError } from "@/lib/api";
import { packageFormSchema, type ClientView, type PackageFormValues } from "@/lib/schemas";
import { isStepUnlocked, stepPath } from "@/lib/workflow";
import { useWorkflowStore } from "@/stores/workflowStore";

const DEFAULT_PACKAGE = "com.generated.client";

/** 3단계: RestClient 자동 생성. 패키지명 입력 → 생성(컴파일 검증) → 소스 확인 → ZIP 다운로드. */
export default function ClientPage() {
  const { specId: specIdParam } = useParams<{ specId: string }>();
  const specId = Number(specIdParam);
  const detail = useWorkflowStore((state) => state.detail);
  const loadSpec = useWorkflowStore((state) => state.loadSpec);

  const [client, setClient] = useState<ClientView | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<PackageFormValues>({
    resolver: zodResolver(packageFormSchema),
    defaultValues: { basePackage: DEFAULT_PACKAGE },
  });

  // 이미 생성한 결과가 있으면 바로 보여주고, 입력란에도 그때 쓴 패키지명을 채운다.
  useEffect(() => {
    let cancelled = false;
    api
      .getClient(specId)
      .then((existing) => {
        if (!cancelled && existing) {
          setClient(existing);
          setValue("basePackage", existing.basePackage);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "생성 결과를 불러오지 못했습니다.");
        }
      })
      .finally(() => !cancelled && setInitialLoading(false));
    return () => {
      cancelled = true;
    };
  }, [specId, setValue]);

  const generate = useCallback(
    async (values: PackageFormValues) => {
      setGenerating(true);
      setError(null);
      try {
        const result = await api.generateClient(specId, values.basePackage);
        setClient(result);
        await loadSpec(specId); // 컴파일 성공 시 상태가 CLIENT_GENERATED로 바뀌어 4단계가 열린다
        if (!result.compiled) {
          toast.error("생성된 코드가 컴파일되지 않았습니다. 진단 내용을 확인하세요.");
        }
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "코드 생성 중 알 수 없는 오류가 발생했습니다.");
      } finally {
        setGenerating(false);
      }
    },
    [specId, loadSpec],
  );

  async function download() {
    setDownloading(true);
    try {
      const blob = await api.downloadClient(specId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `rest-client-${specId}.zip`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "다운로드에 실패했습니다.");
    } finally {
      setDownloading(false);
    }
  }

  if (!detail) {
    return null;
  }

  if (!isStepUnlocked(3, detail.summary.status)) {
    return (
      <Alert data-testid="step-locked">
        <AlertTitle>이전 단계를 먼저 완료해야 합니다</AlertTitle>
        <AlertDescription>
          RestClient 코드는 AI 명세 분석 결과의 메서드명을 사용해 생성합니다.{" "}
          <Link href={stepPath("analysis", specId)} className="font-medium underline underline-offset-4">
            AI 명세 분석으로 이동
          </Link>
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>RestClient 자동 생성</CardTitle>
          <CardDescription>
            분석된 메서드명으로 Spring RestClient 기반 Java 클라이언트와 DTO를 생성하고, 컴파일되는지 바로 검증합니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <form onSubmit={handleSubmit(generate)} className="flex flex-col gap-2" noValidate>
            <label htmlFor="basePackage" className="text-sm font-medium">
              패키지명
            </label>
            <div className="flex flex-col gap-2 sm:flex-row">
              <Input
                id="basePackage"
                {...register("basePackage")}
                placeholder={DEFAULT_PACKAGE}
                aria-invalid={errors.basePackage ? true : undefined}
                disabled={generating}
                className="font-mono sm:max-w-md"
              />
              <Button type="submit" disabled={generating || initialLoading} data-testid="generate-client">
                {generating ? <Loader2 className="animate-spin" /> : <Code2 />}
                {client ? "다시 생성" : "코드 생성"}
              </Button>
            </div>
            {errors.basePackage && (
              <p role="alert" className="text-sm text-destructive">
                {errors.basePackage.message}
              </p>
            )}
          </form>

          {error && <ErrorAlert title="코드 생성에 실패했습니다" message={error} />}
        </CardContent>
      </Card>

      {client && (
        <>
          {client.compiled ? (
            <Alert data-testid="compile-success">
              <CheckCircle2 className="size-4" />
              <AlertTitle>컴파일 성공</AlertTitle>
              <AlertDescription>
                파일 {client.files.length}개가 생성되었고 오류 없이 컴파일됩니다. (패키지 {client.basePackage})
              </AlertDescription>
            </Alert>
          ) : (
            <Alert variant="destructive" data-testid="compile-failure">
              <XCircle className="size-4" />
              <AlertTitle>컴파일 실패 — 다음 단계로 진행할 수 없습니다</AlertTitle>
              <AlertDescription>
                <ul className="list-disc pl-4">
                  {client.diagnostics.map((line) => (
                    <li key={line} className="whitespace-pre-wrap break-words font-mono text-xs">
                      {line}
                    </li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}

          {client.warnings.length > 0 && (
            <Alert>
              <AlertTitle>생성 시 건너뛰거나 대체한 항목 {client.warnings.length}건</AlertTitle>
              <AlertDescription>
                <ul className="list-disc pl-4">
                  {client.warnings.map((warning) => (
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
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <CardTitle>생성된 소스</CardTitle>
                  <CardDescription>{new Date(client.generatedAt).toLocaleString("ko-KR")} 생성</CardDescription>
                </div>
                <Button variant="outline" onClick={download} disabled={!client.compiled || downloading} data-testid="download-client">
                  {downloading ? <Loader2 className="animate-spin" /> : <Download />}
                  ZIP 다운로드
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              <CodeTreeViewer key={client.generatedAt} files={client.files} />
            </CardContent>
          </Card>

          {client.compiled && (
            <div className="flex justify-end">
              <Link href={stepPath("samples", specId)} className={buttonVariants({ size: "lg" })}>
                다음: 샘플 데이터 <ArrowRight />
              </Link>
            </div>
          )}
        </>
      )}
    </>
  );
}
