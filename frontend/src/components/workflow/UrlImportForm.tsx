"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { urlFormSchema, type UrlFormValues } from "@/lib/schemas";

interface UrlImportFormProps {
  disabled?: boolean;
  onSubmitUrl: (url: string) => void;
}

/** Swagger 명세 URL 입력 폼 (React Hook Form + Zod) */
export function UrlImportForm({ disabled, onSubmitUrl }: UrlImportFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<UrlFormValues>({
    resolver: zodResolver(urlFormSchema),
    defaultValues: { url: "" },
  });

  return (
    <form
      onSubmit={handleSubmit((values) => onSubmitUrl(values.url))}
      className="flex flex-col gap-3"
      noValidate
    >
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          {...register("url")}
          type="url"
          placeholder="https://example.com/v3/api-docs"
          aria-label="Swagger 명세 URL"
          aria-invalid={errors.url ? true : undefined}
          disabled={disabled}
        />
        <Button type="submit" disabled={disabled} className="sm:w-32">
          불러오기
        </Button>
      </div>
      {errors.url && (
        <p role="alert" className="text-sm text-destructive">
          {errors.url.message}
        </p>
      )}
      <p className="text-xs text-muted-foreground">
        보안을 위해 사설/내부 네트워크 주소(localhost, 10.x 등)는 사용할 수 없습니다.
      </p>
    </form>
  );
}
