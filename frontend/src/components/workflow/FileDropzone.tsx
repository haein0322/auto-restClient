"use client";

import { useRef, useState } from "react";
import { FileUp } from "lucide-react";
import { cn } from "@/lib/utils";

const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPTED = /\.(json|ya?ml)$/i;

interface FileDropzoneProps {
  disabled?: boolean;
  onFileSelected: (file: File) => void;
  onInvalidFile: (message: string) => void;
}

/** 드래그&드롭 또는 클릭으로 json/yaml 파일을 선택한다. */
export function FileDropzone({ disabled, onFileSelected, onInvalidFile }: FileDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  function handleFile(file: File | undefined) {
    if (!file) {
      return;
    }
    if (!ACCEPTED.test(file.name)) {
      onInvalidFile("json, yaml, yml 파일만 업로드할 수 있습니다.");
      return;
    }
    if (file.size > MAX_BYTES) {
      onInvalidFile("파일 크기는 10MB 이하여야 합니다.");
      return;
    }
    onFileSelected(file);
  }

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={(e) => {
        if (!disabled && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          inputRef.current?.click();
        }
      }}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) {
          setDragging(true);
        }
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        if (!disabled) {
          handleFile(e.dataTransfer.files[0]);
        }
      }}
      className={cn(
        "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-10 text-center transition-colors",
        dragging ? "border-primary bg-primary/5" : "hover:bg-muted",
        disabled && "cursor-not-allowed opacity-50",
      )}
    >
      <FileUp className="size-8 text-muted-foreground" />
      <p className="font-medium">Swagger/OpenAPI 파일을 끌어다 놓거나 클릭해서 선택하세요</p>
      <p className="text-sm text-muted-foreground">.json, .yaml, .yml · 최대 10MB</p>
      <input
        ref={inputRef}
        type="file"
        accept=".json,.yaml,.yml"
        className="hidden"
        data-testid="spec-file-input"
        onChange={(e) => {
          handleFile(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
    </div>
  );
}
