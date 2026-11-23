"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { api, ApiError } from "@/lib/api";
import type { SampleInfo } from "@/lib/schemas";
import { ErrorAlert } from "./ErrorAlert";

interface SampleListProps {
  disabled?: boolean;
  onSelect: (name: string) => void;
}

/** 내장 샘플 명세 목록 */
export function SampleList({ disabled, onSelect }: SampleListProps) {
  const [samples, setSamples] = useState<SampleInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listSamples()
      .then((list) => !cancelled && setSamples(list))
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "샘플 목록을 불러오지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <ErrorAlert message={error} />;
  }
  if (!samples) {
    return <p className="text-sm text-muted-foreground">샘플 목록을 불러오는 중...</p>;
  }

  return (
    <ul className="flex flex-col gap-3">
      {samples.map((sample) => (
        <li
          key={sample.name}
          className="flex flex-col gap-3 rounded-lg border p-4 sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="min-w-0">
            <p className="font-medium">{sample.title}</p>
            <p className="text-sm text-muted-foreground">{sample.description}</p>
          </div>
          <Button
            variant="outline"
            disabled={disabled}
            onClick={() => onSelect(sample.name)}
            data-testid={`load-sample-${sample.name}`}
          >
            불러오기
          </Button>
        </li>
      ))}
    </ul>
  );
}
