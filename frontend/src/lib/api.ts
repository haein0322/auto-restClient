import { z } from "zod";
import {
  analysisViewSchema,
  clientViewSchema,
  progressEventSchema,
  sampleInfoSchema,
  sampleItemSchema,
  sampleSetViewSchema,
  specDetailSchema,
  specSummarySchema,
  sseErrorSchema,
  testDefaultsSchema,
  testRunSummarySchema,
  testRunViewSchema,
  diagnosisViewSchema,
  type AnalysisView,
  type ClientView,
  type ProgressEvent,
  type SampleInfo,
  type SampleItem,
  type SampleSetView,
  type SpecDetail,
  type SpecSummary,
  type TestDefaults,
  type TestOptionsFormValues,
  type TestRunSummary,
  type TestRunView,
  type DiagnosisView,
} from "./schemas";
import { readSse } from "./sse";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8090";

/** 백엔드 공통 응답(ApiResponse)의 오류를 표현하는 예외 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const envelopeSchema = z.object({
  success: z.boolean(),
  data: z.unknown().optional(),
  error: z.object({ code: z.string(), message: z.string() }).optional(),
});

async function request<T>(path: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, init);
  } catch {
    throw new ApiError("NETWORK_ERROR", "서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.", 0);
  }

  const body: unknown = await response.json().catch(() => null);
  const envelope = envelopeSchema.safeParse(body);
  if (!envelope.success) {
    throw new ApiError("INVALID_RESPONSE", "서버 응답 형식이 올바르지 않습니다.", response.status);
  }
  if (!envelope.data.success || !response.ok) {
    const error = envelope.data.error;
    throw new ApiError(
      error?.code ?? "UNKNOWN",
      error?.message ?? "알 수 없는 오류가 발생했습니다.",
      response.status,
    );
  }

  const parsed = schema.safeParse(envelope.data.data);
  if (!parsed.success) {
    throw new ApiError("INVALID_RESPONSE", "서버 응답의 데이터 구조가 예상과 다릅니다.", response.status);
  }
  return parsed.data;
}

const jsonPost = (payload: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
});

export const api = {
  listSpecs: (): Promise<SpecSummary[]> => request("/api/specs", z.array(specSummarySchema)),

  getSpec: (id: number): Promise<SpecDetail> => request(`/api/specs/${id}`, specDetailSchema),

  listSamples: (): Promise<SampleInfo[]> => request("/api/specs/samples", z.array(sampleInfoSchema)),

  loadSample: (name: string): Promise<SpecSummary> =>
    request(`/api/specs/samples/${encodeURIComponent(name)}`, specSummarySchema, { method: "POST" }),

  uploadFile: (file: File): Promise<SpecSummary> => {
    const form = new FormData();
    form.append("file", file);
    return request("/api/specs", specSummarySchema, { method: "POST", body: form });
  },

  importFromUrl: (url: string): Promise<SpecSummary> =>
    request("/api/specs/url", specSummarySchema, jsonPost({ url })),

  /** 저장된 최신 분석 결과. 아직 분석하지 않았으면 null. */
  getAnalysis: async (id: number): Promise<AnalysisView | null> => {
    try {
      return await request(`/api/specs/${id}/analysis`, analysisViewSchema);
    } catch (e) {
      if (e instanceof ApiError && e.code === "ANALYSIS_NOT_FOUND") {
        return null;
      }
      throw e;
    }
  },

  /** 저장된 최신 생성 결과. 아직 생성하지 않았으면 null. */
  getClient: async (id: number): Promise<ClientView | null> => {
    try {
      return await request(`/api/specs/${id}/client`, clientViewSchema);
    } catch (e) {
      if (e instanceof ApiError && e.code === "CLIENT_NOT_FOUND") {
        return null;
      }
      throw e;
    }
  },

  generateClient: (id: number, basePackage: string): Promise<ClientView> =>
    request(`/api/specs/${id}/client`, clientViewSchema, jsonPost({ basePackage })),

  /** 생성된 클라이언트 ZIP을 Blob으로 내려받는다. */
  downloadClient: async (id: number): Promise<Blob> => {
    let response: Response;
    try {
      response = await fetch(`${API_BASE_URL}/api/specs/${id}/client/download`);
    } catch {
      throw new ApiError("NETWORK_ERROR", "서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.", 0);
    }
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null);
      const envelope = envelopeSchema.safeParse(body);
      throw new ApiError(
        envelope.success ? (envelope.data.error?.code ?? "UNKNOWN") : "INVALID_RESPONSE",
        envelope.success ? (envelope.data.error?.message ?? "다운로드에 실패했습니다.") : "서버 응답 형식이 올바르지 않습니다.",
        response.status,
      );
    }
    return response.blob();
  },

  /** 저장된 샘플 목록. 아직 생성하지 않았으면 null. */
  getSamples: async (id: number): Promise<SampleSetView | null> => {
    try {
      return await request(`/api/specs/${id}/samples`, sampleSetViewSchema);
    } catch (e) {
      if (e instanceof ApiError && e.code === "SAMPLES_NOT_FOUND") {
        return null;
      }
      throw e;
    }
  },

  generateSamples: (id: number): Promise<SampleSetView> =>
    request(`/api/specs/${id}/samples`, sampleSetViewSchema, { method: "POST" }),

  updateSample: (id: number, operationId: string, sampleRequest: unknown): Promise<SampleItem> =>
    request(`/api/specs/${id}/samples/${encodeURIComponent(operationId)}`, sampleItemSchema, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ request: sampleRequest }),
    }),

  regenerateSample: (id: number, operationId: string): Promise<SampleItem> =>
    request(`/api/specs/${id}/samples/${encodeURIComponent(operationId)}/regenerate`, sampleItemSchema, {
      method: "POST",
    }),

  /** AI 분석을 시작하고 SSE 진행 상황을 콜백으로 전달한다. 완료되면 결과를, 실패하면 ApiError를 던진다. */
  runAnalysis: (
    id: number,
    onProgress: (event: ProgressEvent) => void,
    signal?: AbortSignal,
  ): Promise<AnalysisView> =>
    streamSse(`/api/specs/${id}/analysis`, { method: "POST" }, analysisViewSchema, "분석", onProgress, signal),

  getTestDefaults: (id: number): Promise<TestDefaults> => request(`/api/specs/${id}/test/defaults`, testDefaultsSchema),

  listTestRuns: (id: number): Promise<TestRunSummary[]> =>
    request(`/api/specs/${id}/test/runs`, z.array(testRunSummarySchema)),

  getTestRun: (id: number, runId: number): Promise<TestRunView> =>
    request(`/api/specs/${id}/test/runs/${runId}`, testRunViewSchema),

  /** 통신 테스트를 실행하고 SSE 진행 상황을 콜백으로 전달한다. */
  runTest: (
    id: number,
    options: TestOptionsFormValues,
    onProgress: (event: ProgressEvent) => void,
    signal?: AbortSignal,
  ): Promise<TestRunView> =>
    streamSse(
      `/api/specs/${id}/test/runs`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...options, baseUrl: options.baseUrl.trim(), timeoutSeconds: null }),
      },
      testRunViewSchema,
      "통신 테스트",
      onProgress,
      signal,
    ),

  diagnoseTestRun: (id: number, runId: number): Promise<DiagnosisView> =>
    request(`/api/specs/${id}/test/runs/${runId}/diagnosis`, diagnosisViewSchema, { method: "POST" }),
};

/**
 * POST로 시작하는 SSE 스트림을 읽는다. 이벤트: progress → (result | error).
 * label은 오류 문구에 쓰는 작업 이름이다(예: "분석", "통신 테스트").
 */
async function streamSse<T>(
  path: string,
  init: RequestInit,
  resultSchema: z.ZodType<T>,
  label: string,
  onProgress: (event: ProgressEvent) => void,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, signal });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw e;
    }
    throw new ApiError("NETWORK_ERROR", "서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해주세요.", 0);
  }

  // 스트림이 시작되기 전의 오류(404, 입력 검증 등)는 일반 JSON 응답으로 온다.
  const isStream = response.headers.get("content-type")?.includes("text/event-stream") ?? false;
  if (!response.ok || !isStream || !response.body) {
    const body: unknown = await response.json().catch(() => null);
    const envelope = envelopeSchema.safeParse(body);
    throw new ApiError(
      envelope.success ? (envelope.data.error?.code ?? "UNKNOWN") : "INVALID_RESPONSE",
      envelope.success
        ? (envelope.data.error?.message ?? `${label}을(를) 시작하지 못했습니다.`)
        : "서버 응답 형식이 올바르지 않습니다.",
      response.status,
    );
  }

  for await (const message of readSse(response.body)) {
    const payload: unknown = JSON.parse(message.data);
    if (message.event === "progress") {
      const progress = progressEventSchema.safeParse(payload);
      if (progress.success) {
        onProgress(progress.data);
      }
    } else if (message.event === "result") {
      const result = resultSchema.safeParse(payload);
      if (!result.success) {
        throw new ApiError("INVALID_RESPONSE", `${label} 결과의 데이터 구조가 예상과 다릅니다.`, 200);
      }
      return result.data;
    } else if (message.event === "error") {
      const error = sseErrorSchema.safeParse(payload);
      throw new ApiError(
        error.success ? error.data.code : "UNKNOWN",
        error.success ? error.data.message : `${label} 중 오류가 발생했습니다.`,
        200,
      );
    }
  }
  throw new ApiError("STREAM_CLOSED", `${label} 결과를 받기 전에 연결이 종료되었습니다. 이력에서 다시 확인해주세요.`, 200);
}
