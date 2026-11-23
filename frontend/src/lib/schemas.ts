import { z } from "zod";

/** 백엔드 DTO(SpecDtos)와 1:1로 대응하는 Zod 스키마. 응답은 반드시 이 스키마로 검증한다. */

export const specStatusSchema = z.enum([
  "PARSED",
  "ANALYZED",
  "CLIENT_GENERATED",
  "SAMPLES_READY",
  "TESTED",
]);
export type SpecStatus = z.infer<typeof specStatusSchema>;

export const specSummarySchema = z.object({
  id: z.number(),
  title: z.string(),
  version: z.string().nullish(),
  sourceType: z.string(),
  endpointCount: z.number(),
  schemaCount: z.number(),
  status: specStatusSchema,
  createdAt: z.string(),
});
export type SpecSummary = z.infer<typeof specSummarySchema>;

export const paramInfoSchema = z.object({
  name: z.string(),
  in: z.string(),
  required: z.boolean(),
  type: z.string(),
});

export const endpointInfoSchema = z.object({
  operationId: z.string(),
  method: z.string(),
  path: z.string(),
  summary: z.string().nullish(),
  tag: z.string(),
  secured: z.boolean(),
  parameters: z.array(paramInfoSchema),
  hasRequestBody: z.boolean(),
  requestBodyRequired: z.boolean(),
  responseCodes: z.array(z.string()),
});
export type EndpointInfo = z.infer<typeof endpointInfoSchema>;

export const securitySchemeSchema = z.object({
  name: z.string(),
  type: z.string().nullish(),
  location: z.string().nullish(),
  keyName: z.string().nullish(),
});

export const specDetailSchema = z.object({
  summary: specSummarySchema,
  description: z.string().nullish(),
  servers: z.array(z.string()),
  securitySchemes: z.array(securitySchemeSchema),
  endpoints: z.array(endpointInfoSchema),
  warnings: z.array(z.string()),
});
export type SpecDetail = z.infer<typeof specDetailSchema>;

export const sampleInfoSchema = z.object({
  name: z.string(),
  title: z.string(),
  description: z.string(),
});
export type SampleInfo = z.infer<typeof sampleInfoSchema>;

/** URL 불러오기 폼 검증 */
export const urlFormSchema = z.object({
  url: z
    .string()
    .trim()
    .min(1, "URL을 입력해주세요.")
    .refine((value) => /^https?:\/\/.+/i.test(value), "http:// 또는 https:// 로 시작해야 합니다."),
});
export type UrlFormValues = z.infer<typeof urlFormSchema>;

/** AI 분석(2단계) 응답 스키마. 백엔드 AnalysisDtos와 대응한다. */

export const riskLevelSchema = z.enum(["SAFE", "MUTATING", "DESTRUCTIVE"]);
export type RiskLevel = z.infer<typeof riskLevelSchema>;

export const endpointInsightSchema = z.object({
  operationId: z.string(),
  suggestedMethodName: z.string(),
  purpose: z.string(),
  risk: riskLevelSchema,
  notes: z.string().nullish(),
});
export type EndpointInsight = z.infer<typeof endpointInsightSchema>;

export const dependencyInfoSchema = z.object({
  fromOperationId: z.string(),
  toOperationId: z.string(),
  reason: z.string().nullish(),
});

export const analysisReportSchema = z.object({
  provider: z.string(),
  model: z.string(),
  summary: z.string(),
  authentication: z.object({
    required: z.boolean(),
    scheme: z.string().nullish(),
    description: z.string(),
  }),
  endpoints: z.array(endpointInsightSchema),
  dependencies: z.array(dependencyInfoSchema),
  testOrder: z.array(z.string()),
  cautions: z.array(z.string()),
  warnings: z.array(z.string()),
});
export type AnalysisReport = z.infer<typeof analysisReportSchema>;

export const analysisViewSchema = z.object({
  specId: z.number(),
  analyzedAt: z.string(),
  report: analysisReportSchema,
});
export type AnalysisView = z.infer<typeof analysisViewSchema>;

export const progressEventSchema = z.object({
  step: z.string(),
  message: z.string(),
  percent: z.number(),
});
export type ProgressEvent = z.infer<typeof progressEventSchema>;

export const sseErrorSchema = z.object({ code: z.string(), message: z.string() });

/** RestClient 코드 생성(3단계) 응답 스키마. 백엔드 ClientDtos와 대응한다. */

export const generatedFileSchema = z.object({
  path: z.string(),
  content: z.string(),
});
export type GeneratedFile = z.infer<typeof generatedFileSchema>;

export const clientViewSchema = z.object({
  specId: z.number(),
  generatedAt: z.string(),
  compiled: z.boolean(),
  basePackage: z.string(),
  diagnostics: z.array(z.string()),
  warnings: z.array(z.string()),
  files: z.array(generatedFileSchema),
});
export type ClientView = z.infer<typeof clientViewSchema>;

/** 패키지명 입력 검증. 서버 규칙(소문자/숫자/밑줄, 점 구분, 키워드 금지)과 맞춘다. */
const JAVA_KEYWORDS = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
  "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
  "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
  "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
  "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
  "volatile", "while", "true", "false", "null", "var", "record", "yield",
]);

export const packageFormSchema = z.object({
  basePackage: z
    .string()
    .trim()
    .min(1, "패키지명을 입력해주세요.")
    .max(100, "패키지명은 100자 이하여야 합니다.")
    .regex(/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/, "소문자 영문/숫자/밑줄을 점으로 구분해 입력하세요. (예: com.example.shop)")
    .refine((value) => !value.split(".").some((part) => JAVA_KEYWORDS.has(part)), "Java 예약어는 패키지명에 쓸 수 없습니다."),
});
export type PackageFormValues = z.infer<typeof packageFormSchema>;

/** 샘플 데이터(4단계) 응답 스키마. 백엔드 SampleDtos와 대응한다. */

export const sampleSourceSchema = z.enum(["RULE", "AI", "FALLBACK", "USER"]);
export type SampleSource = z.infer<typeof sampleSourceSchema>;

export const sampleItemSchema = z.object({
  operationId: z.string(),
  method: z.string(),
  path: z.string(),
  /** {pathParams, queryParams, headers, body} 구조의 임의 JSON */
  request: z.unknown(),
  valid: z.boolean(),
  errors: z.array(z.string()),
  source: sampleSourceSchema,
  updatedAt: z.string(),
});
export type SampleItem = z.infer<typeof sampleItemSchema>;

export const sampleSetViewSchema = z.object({
  specId: z.number(),
  provider: z.string(),
  validCount: z.number(),
  total: z.number(),
  warnings: z.array(z.string()),
  samples: z.array(sampleItemSchema),
});
export type SampleSetView = z.infer<typeof sampleSetViewSchema>;

/** 통신 테스트(5단계) 응답 스키마. 백엔드 TestDtos와 대응한다. */

export const testDefaultsSchema = z.object({
  baseUrl: z.string(),
  apiKey: z.string(),
  builtInMock: z.boolean(),
});
export type TestDefaults = z.infer<typeof testDefaultsSchema>;

export const testOutcomeSchema = z.enum(["PASS", "FAIL", "SKIPPED"]);
export type TestOutcome = z.infer<typeof testOutcomeSchema>;

export const testCaseSchema = z.object({
  operationId: z.string(),
  method: z.string(),
  path: z.string(),
  outcome: testOutcomeSchema,
  statusCode: z.number().nullable(),
  elapsedMs: z.number().nullable(),
  schemaValid: z.boolean().nullable(),
  /** 실제로 전송한 요청(체이닝·고유화 반영 후) */
  request: z.unknown(),
  responseBody: z.string().nullable(),
  failureReason: z.string().nullable(),
});
export type TestCase = z.infer<typeof testCaseSchema>;

export const caseDiagnosisSchema = z.object({
  operationId: z.string(),
  likelyCause: z.string(),
  suggestion: z.string(),
});
export type CaseDiagnosis = z.infer<typeof caseDiagnosisSchema>;

export const diagnosisViewSchema = z.object({
  provider: z.string(),
  diagnosedAt: z.string(),
  summary: z.string(),
  cases: z.array(caseDiagnosisSchema),
});
export type DiagnosisView = z.infer<typeof diagnosisViewSchema>;

export const testRunViewSchema = z.object({
  runId: z.number(),
  specId: z.number(),
  baseUrl: z.string(),
  createdAt: z.string(),
  total: z.number(),
  passed: z.number(),
  failed: z.number(),
  skipped: z.number(),
  cases: z.array(testCaseSchema),
  diagnosis: diagnosisViewSchema.nullable(),
});
export type TestRunView = z.infer<typeof testRunViewSchema>;

export const testRunSummarySchema = z.object({
  runId: z.number(),
  baseUrl: z.string(),
  createdAt: z.string(),
  total: z.number(),
  passed: z.number(),
  failed: z.number(),
  skipped: z.number(),
});
export type TestRunSummary = z.infer<typeof testRunSummarySchema>;

/** 실행 옵션 입력 검증. 서버가 다시 검증하므로 여기서는 형식 오류만 즉시 알려준다. */
export const testOptionsFormSchema = z.object({
  baseUrl: z
    .string()
    .trim()
    .min(1, "대상 서버 주소를 입력해주세요.")
    .refine((value) => /^https?:\/\/[^\s/]+/i.test(value), "http:// 또는 https:// 로 시작하는 주소를 입력하세요."),
  apiKey: z.string(),
  allowDestructive: z.boolean(),
  confirmExternal: z.boolean(),
});
export type TestOptionsFormValues = z.infer<typeof testOptionsFormSchema>;
