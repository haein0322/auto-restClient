"use client";

import { useMemo, useState } from "react";
import { Check, Copy, File, Folder } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import type { GeneratedFile } from "@/lib/schemas";
import { cn } from "@/lib/utils";

interface CodeTreeViewerProps {
  files: GeneratedFile[];
}

/** 파일을 디렉터리별로 묶는다. 경로 정렬 순서를 유지하되 루트 파일(README 등)은 마지막에 둔다. */
function groupByDirectory(files: GeneratedFile[]): { directory: string; files: GeneratedFile[] }[] {
  const groups = new Map<string, GeneratedFile[]>();
  for (const file of [...files].sort((a, b) => a.path.localeCompare(b.path))) {
    const slash = file.path.lastIndexOf("/");
    const directory = slash >= 0 ? file.path.slice(0, slash) : "";
    groups.set(directory, [...(groups.get(directory) ?? []), file]);
  }
  return [...groups.entries()]
    .map(([directory, groupFiles]) => ({ directory, files: groupFiles }))
    .sort((a, b) => Number(a.directory === "") - Number(b.directory === "") || a.directory.localeCompare(b.directory));
}

/** 생성된 소스 트리와 코드 보기(줄 번호, 복사). 구문 강조는 없이 고정폭으로 표시한다. */
export function CodeTreeViewer({ files }: CodeTreeViewerProps) {
  const groups = useMemo(() => groupByDirectory(files), [files]);
  const initial = files.find((file) => file.path.endsWith("/ApiClient.java")) ?? files[0];
  const [selectedPath, setSelectedPath] = useState(initial?.path ?? "");
  const [copied, setCopied] = useState(false);

  const selected = files.find((file) => file.path === selectedPath) ?? initial;
  if (!selected) {
    return <p className="text-sm text-muted-foreground">생성된 파일이 없습니다.</p>;
  }
  const lines = selected.content.replace(/\n$/, "").split("\n");

  async function copy() {
    try {
      await navigator.clipboard.writeText(selected.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("클립보드에 복사하지 못했습니다. 브라우저 권한을 확인해주세요.");
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
      <nav aria-label="생성된 파일" className="max-h-[32rem] overflow-auto rounded-lg border p-2 text-sm">
        {groups.map((group) => (
          <div key={group.directory} className="mb-2 last:mb-0">
            <p className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground">
              <Folder className="size-3.5 shrink-0" />
              <span className="break-all">{group.directory || "(루트)"}</span>
            </p>
            <ul>
              {group.files.map((file) => (
                <li key={file.path}>
                  <button
                    type="button"
                    onClick={() => setSelectedPath(file.path)}
                    aria-current={file.path === selected.path}
                    className={cn(
                      "flex w-full items-center gap-1.5 rounded px-2 py-1 text-left hover:bg-muted",
                      file.path === selected.path && "bg-muted font-medium",
                    )}
                  >
                    <File className="size-3.5 shrink-0" />
                    <span className="truncate">{file.path.slice(file.path.lastIndexOf("/") + 1)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      <div className="min-w-0 rounded-lg border">
        <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
          <p className="min-w-0 truncate font-mono text-xs" title={selected.path}>
            {selected.path}
          </p>
          <Button variant="ghost" size="sm" onClick={copy} aria-label="코드 복사">
            {copied ? <Check /> : <Copy />}
            {copied ? "복사됨" : "복사"}
          </Button>
        </div>
        <pre className="max-h-[32rem] overflow-auto p-3 text-xs leading-5" data-testid="code-view">
          <code>
            {lines.map((line, index) => (
              <span key={index} className="flex">
                <span className="mr-4 inline-block w-8 shrink-0 select-none text-right text-muted-foreground">
                  {index + 1}
                </span>
                <span className="whitespace-pre">{line || " "}</span>
              </span>
            ))}
          </code>
        </pre>
      </div>
    </div>
  );
}
