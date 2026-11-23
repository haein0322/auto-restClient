import { SpecHistory } from "@/components/workflow/SpecHistory";
import { StepNav } from "@/components/workflow/StepNav";
import { UploadPanel } from "@/components/workflow/UploadPanel";

/** 1단계: Swagger 업로드 (명세 등록 전 시작 화면) */
export default function HomePage() {
  return (
    <>
      <StepNav current="upload" />
      <UploadPanel />
      <SpecHistory />
    </>
  );
}
