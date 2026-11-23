"use client";

import { useEffect } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, Play } from "lucide-react";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { testOptionsFormSchema, type TestDefaults, type TestOptionsFormValues } from "@/lib/schemas";

interface TestOptionsFormProps {
  defaults: TestDefaults;
  running: boolean;
  onSubmit: (values: TestOptionsFormValues) => void;
}

/** 통신 테스트 실행 옵션: 대상 서버 주소, API 키, 위험 호출·외부 서버 허용 확인 */
export function TestOptionsForm({ defaults, running, onSubmit }: TestOptionsFormProps) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<TestOptionsFormValues>({
    resolver: zodResolver(testOptionsFormSchema),
    defaultValues: {
      baseUrl: defaults.baseUrl,
      apiKey: defaults.apiKey,
      allowDestructive: false,
      confirmExternal: false,
    },
  });

  // 기본값이 늦게 도착하거나 명세가 바뀌면 입력란에 반영한다.
  useEffect(() => {
    reset({ baseUrl: defaults.baseUrl, apiKey: defaults.apiKey, allowDestructive: false, confirmExternal: false });
  }, [defaults, reset]);

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" noValidate>
      <div className="grid gap-4 md:grid-cols-2">
        <label className="flex flex-col gap-1.5 text-sm">
          <span className="font-medium">대상 서버 주소 (Base URL)</span>
          <Input
            {...register("baseUrl")}
            placeholder="http://localhost:8090/mock"
            aria-invalid={errors.baseUrl ? true : undefined}
            data-testid="test-base-url"
          />
          {errors.baseUrl && (
            <span role="alert" className="text-xs text-destructive" data-testid="test-base-url-error">
              {errors.baseUrl.message}
            </span>
          )}
        </label>

        <label className="flex flex-col gap-1.5 text-sm">
          <span className="font-medium">API 키</span>
          <Input
            {...register("apiKey")}
            type="password"
            autoComplete="off"
            placeholder="명세가 요구하는 인증 키"
            data-testid="test-api-key"
          />
          <span className="text-xs text-muted-foreground">
            {defaults.builtInMock ? "내장 Mock 서버용 데모 키가 채워져 있습니다. " : ""}키는 저장하지 않고 이번 실행에만 사용합니다.
          </span>
        </label>
      </div>

      <div className="flex flex-col gap-2 text-sm">
        <label className="flex items-start gap-2">
          <input type="checkbox" className="mt-0.5 size-4" {...register("allowDestructive")} data-testid="test-allow-destructive" />
          <span>
            <span className="font-medium">삭제 호출 허용</span>
            <span className="block text-xs text-muted-foreground">
              DELETE 같은 위험 호출도 실행합니다. 끄면 건너뜁니다. 방금 만든 데이터를 대상으로 호출합니다.
            </span>
          </span>
        </label>
        <label className="flex items-start gap-2">
          <input type="checkbox" className="mt-0.5 size-4" {...register("confirmExternal")} data-testid="test-confirm-external" />
          <span>
            <span className="font-medium">외부 서버 호출 확인</span>
            <span className="block text-xs text-muted-foreground">
              localhost 밖의 실제 서버에 요청이 전송됩니다. 외부 주소를 쓸 때만 선택하세요.
            </span>
          </span>
        </label>
      </div>

      <div>
        <Button type="submit" disabled={running} data-testid="start-test">
          {running ? <Loader2 className="animate-spin" /> : <Play />}
          통신 테스트 실행
        </Button>
      </div>
    </form>
  );
}
