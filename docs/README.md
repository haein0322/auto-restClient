# AI RestClient

Swagger(OpenAPI) 명세를 AI로 분석해 Java `RestClient` 코드를 자동 생성하고, 샘플 데이터로 실제 통신 테스트까지 수행하는 시스템입니다.

## 워크플로 5단계

| 단계 | 화면 | 하는 일 |
|------|------|---------|
| 1 | Swagger 업로드 | 파일·URL·내장 샘플로 명세를 등록하고 파싱·검증 (OpenAPI 3.x, Swagger 2.0 변환 지원). 엔드포인트 목록의 화살표로 미리 만든 샘플 데이터를 확인 |
| 2 | AI 명세 분석 | 엔드포인트 목적·위험도(조회/변경/삭제)·호출 순서·의존관계·인증 방식 분석. 서버가 명세와 대조해 검증 |
| 3 | RestClient 생성 | 분석 결과의 메서드명으로 DTO와 `RestClient` 클라이언트를 생성, 인메모리 컴파일로 검증, ZIP 다운로드 |
| 4 | 샘플 데이터 | 엔드포인트별 요청 샘플 생성. 명세(필수값·enum·범위·형식)로 검증하고 편집·재생성 가능 |
| 5 | 통신 테스트 | 생성된 클라이언트로 샘플을 실제 호출. 상태 코드·응답 스키마 검증, 실패 원인 분석 |

## 구성

```
AI-RestClient/
├─ backend/    Spring Boot 3 (Java 17), H2 + Flyway, Gradle
├─ frontend/   Next.js 15, React 19, Tailwind, shadcn/ui, Zustand, React Hook Form + Zod
├─ samples/    명세 파일 (sample-api.yaml: 화면에 노출되는 샘플 API / 나머지는 자동 테스트 전용)
├─ e2e/        Playwright 브라우저 E2E
└─ docs/       문서
```

백엔드는 Controller → Service → Repository 계층과 DTO 패턴을 따르고, 모든 응답은 `{ success, data, error }` 형식입니다.

## 실행

```bash
# 백엔드 (포트 8090)
cd backend
./gradlew bootRun

# 프론트엔드 (포트 3000)
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:3000 을 엽니다. 1단계에서 "샘플 불러오기"의 **샘플 API**(엔드포인트 3개)를 불러오면 키 없이 전체 흐름을 체험할 수 있습니다.

## AI 설정

기본값은 키가 필요 없는 규칙 기반(`fake`) 모드입니다. 실제 Claude를 쓰려면 환경변수를 지정해 백엔드를 실행합니다.

| 환경변수 | 설명 | 기본값 |
|----------|------|--------|
| `APP_AI_PROVIDER` | `fake` 또는 `claude` | `fake` |
| `ANTHROPIC_API_KEY` | Claude API 키 | 없음 |

AI 응답은 신뢰하지 않고 서버가 항상 검증합니다.
- 분석: 명세에 없는 엔드포인트나 잘못된 의존관계는 정규화 단계에서 걸러냅니다.
- 샘플: 검증 실패 시 오류를 AI에게 돌려줘 최대 2회 재생성하고, 끝내 실패하면 규칙 기반 값으로 대체합니다(출처 `FALLBACK`).
- 원인 분석: AI 호출이 실패하거나 일부 케이스가 누락되면 규칙 기반 진단으로 채웁니다.

## 샘플 API와 내장 Mock 서버

실제 테스트 대상 서버가 없어도 통신 테스트를 해볼 수 있도록 `/mock` 경로에 샘플 API(`samples/sample-api.yaml`)를 구현한 Mock이 내장되어 있습니다.
- 엔드포인트 3개: `listItems`(GET /items), `createItem`(POST /items), `getItem`(GET /items/{itemId})
- Base URL: `http://localhost:8090/mock`
- 인증: `X-API-KEY: demo-key`

### 1단계 샘플 데이터 미리보기

명세를 등록하면 엔드포인트 목록 왼쪽의 화살표로 각 엔드포인트의 샘플 요청 데이터를 펼쳐 볼 수 있습니다(`GET /api/specs/{id}/samples/preview`).
- 명세만으로 규칙 기반으로 만들며 AI를 호출하지 않고, 저장도 하지 않습니다. 4단계에서 만드는 샘플과는 별개입니다.

### 자동 테스트 전용 명세

`shop-sample`(12개 엔드포인트, DELETE·ID 연쇄·중복 검증 등), `legacy-v2-sample`(Swagger 2.0), `invalid-sample`(오류 명세)은 화면 목록에는 나오지 않고 자동 테스트에서만 이름으로 불러옵니다. 쇼핑몰 Mock(`/mock/products` 등)도 이 테스트용입니다.

## 통신 테스트 동작 방식

- 3단계에서 생성한 클라이언트를 메모리에서 컴파일해 격리 ClassLoader로 로드하고, 4단계 샘플로 리플렉션 호출합니다.
- 호출 순서는 AI 분석의 `testOrder`를 따르며, 앞 호출 응답의 `id`를 뒤 호출의 경로 파라미터에 넣습니다. 삭제도 방금 만든 데이터를 대상으로 합니다.
- 이메일처럼 중복 불가 값은 실행마다 고유하게 바꿔 반복 실행해도 409가 나지 않습니다.
- 응답은 실제 상태 코드와 원본 본문을 가로채 명세 스키마로 검증합니다.

### 안전장치
- 허용 호스트(`localhost`, `127.0.0.1`, `[::1]`) 밖의 주소는 "외부 서버 호출 확인"을 선택해야 실행됩니다. 허용 목록은 `app.test.allowed-hosts`로 바꿀 수 있습니다.
- `DELETE` 등 위험 호출은 "삭제 호출 허용"을 선택해야 실행하고, 아니면 건너뜁니다.
- API 키는 저장하지 않고 해당 실행에만 사용합니다.

## API 요약

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET/POST | `/api/specs`, `/api/specs/url`, `/api/specs/samples/{name}` | 명세 목록·등록 |
| GET | `/api/specs/{id}` | 명세 상세 |
| POST (SSE) / GET | `/api/specs/{id}/analysis` | AI 분석 실행(진행률 스트림) / 최신 결과 |
| POST / GET | `/api/specs/{id}/client` | 클라이언트 생성 / 조회 |
| GET | `/api/specs/{id}/client/download` | ZIP 다운로드 |
| POST / GET | `/api/specs/{id}/samples` | 샘플 전체 생성 / 조회 |
| PUT / POST | `/api/specs/{id}/samples/{operationId}`, `.../regenerate` | 샘플 편집·재생성 |
| GET | `/api/specs/{id}/samples/preview` | 1단계 미리보기 샘플(저장 안 함) |
| GET | `/api/specs/{id}/test/defaults` | 입력란 기본값 |
| POST (SSE) | `/api/specs/{id}/test/runs` | 통신 테스트 실행 |
| GET | `/api/specs/{id}/test/runs`, `.../runs/{runId}` | 실행 이력·결과 |
| POST | `/api/specs/{id}/test/runs/{runId}/diagnosis` | 실패 원인 분석 |

SSE 이벤트는 `progress` → (`result` | `error`) 순서입니다. 입력 검증 오류는 스트림 시작 전에 일반 JSON으로 응답합니다.

## 테스트

```bash
# 백엔드 단위·통합 테스트 (101건)
cd backend && ./gradlew test

# 프론트엔드 정적 검사
cd frontend && npx tsc --noEmit && npx eslint src

# 브라우저 E2E (백엔드·프론트엔드를 먼저 실행)
cd e2e && npm install && npx playwright install chromium
npm run test:step1-4   # 1~4단계 (44건)
npm run test:step5     # 5단계 (19건)
npm test               # 전체
```

- 1~4단계: 샘플·파일·URL 등록과 오류 처리(잘못된 명세, 확장자, SSRF 방어), 단계 잠금, AI 분석과 새로고침 복원, 패키지명 검증·컴파일·소스 뷰어·ZIP 다운로드, 샘플 생성·편집·재생성·검증을 화면에서 따라가며 확인합니다.
- 5단계: 1~4단계를 API로 준비한 뒤 잠금, 입력 검증, 외부 호스트 거부, 통과/건너뜀, 반복 실행, 실패 원인 분석, 새로고침 복원을 확인합니다.
- 실패 시와 주요 화면의 스크린샷은 `e2e/e2e-output/`에 저장됩니다.

## 알려진 한계

- 실제 Claude의 분석·샘플·원인 분석 품질은 키가 없어 검증하지 못했습니다. 요청 형태와 재시도·대체 루프만 스텁으로 확인했습니다.
- 개발 DB는 `backend/data/`의 H2 파일이며, 테스트는 인메모리 DB로 격리됩니다.
