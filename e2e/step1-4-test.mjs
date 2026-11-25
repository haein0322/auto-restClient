// 1~4단계(업로드 → AI 분석 → RestClient 생성 → 샘플 데이터) 브라우저 E2E.
// 백엔드(8090)와 프론트엔드(3000)를 먼저 실행한 뒤 `npm run test:step1-4`로 실행한다.
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const API = process.env.E2E_API_URL ?? "http://localhost:8090";
const WEB = process.env.E2E_WEB_URL ?? "http://localhost:3000";
const SAMPLES = resolve(dirname(fileURLToPath(import.meta.url)), "../samples");

mkdirSync("e2e-output", { recursive: true });

const results = [];
let failed = 0;

function check(name, ok, extra = "") {
  results.push(`${ok ? "PASS" : "FAIL"} ${name}${ok ? "" : " :: " + extra}`);
  if (!ok) failed++;
}

/** 실패해도 다음 검증을 계속하도록 각 검증을 감싼다. */
async function step(name, body) {
  try {
    await body();
  } catch (e) {
    failed++;
    results.push(`FAIL ${name} :: 예외 ${String(e.message).split("\n")[0]}`);
  }
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const consoleErrors = [];
page.on("console", (m) => m.type() === "error" && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push(String(e)));

/** Next.js의 빈 라우트 안내(role=alert)와 구분하기 위해 문구로 오류 알림을 찾는다. */
const alertWith = (text) => page.getByRole("alert").filter({ hasText: text });

async function openUploadHome() {
  await page.goto(WEB);
  await page.getByRole("tab", { name: "샘플 불러오기" }).waitFor({ timeout: 20000 });
}

async function loadSampleApi() {
  await openUploadHome();
  await page.getByTestId("load-sample-sample-api").waitFor({ timeout: 20000 });
  const listText = await page.locator("main").innerText();
  check("[1단계] 샘플 목록은 '샘플 API' 하나만 표시", (await page.locator('[data-testid^="load-sample-"]').count()) === 1 && listText.includes("샘플 API") && !listText.includes("쇼핑몰") && !listText.includes("12개"), listText);
  await page.getByTestId("load-sample-sample-api").click();
  await page.waitForURL(/\/specs\/\d+\/upload/, { timeout: 30000 });
  return Number(page.url().match(/specs\/(\d+)/)[1]);
}

let specId = 0;

try {
  // ───────────── 1단계: Swagger 업로드 ─────────────
  await step("1단계 샘플 불러오기", async () => {
    specId = await loadSampleApi();
    await page.getByText("엔드포인트 3개").first().waitFor({ timeout: 20000 });
    check("[1단계] 샘플 등록 후 결과 화면으로 이동하고 엔드포인트 3개 표시", true);

    // 엔드포인트 목록의 화살표를 누르면 미리 만들어 둔 샘플 데이터가 펼쳐진다.
    const toggle = page.getByTestId("endpoint-toggle-createItem");
    await toggle.waitFor({ timeout: 20000 });
    check("[1단계] 엔드포인트 3개 모두 화살표 제공", (await page.locator('[data-testid^="endpoint-toggle-"]').count()) === 3);
    check("[1단계] 처음에는 샘플 데이터가 접혀 있음", (await page.getByTestId("endpoint-sample-createItem").count()) === 0);
    await toggle.click();
    const sampleBox = page.getByTestId("endpoint-sample-createItem");
    await sampleBox.waitFor({ timeout: 5000 });
    const sampleText = await sampleBox.innerText();
    check("[1단계] 화살표를 누르면 샘플 데이터(요청 본문) 표시", sampleText.includes('"body"') && sampleText.includes('"name"') && sampleText.includes('"price"'), sampleText);
    await page.screenshot({ path: "e2e-output/step1-preview.png", fullPage: true });
    await toggle.click();
    check("[1단계] 다시 누르면 접힘", (await page.getByTestId("endpoint-sample-createItem").count()) === 0);
    await page.getByTestId("endpoint-toggle-getItem").click();
    check("[1단계] 경로 파라미터 샘플 표시", (await page.getByTestId("endpoint-sample-getItem").innerText()).includes("itemId"));

    const nav = page.getByRole("navigation", { name: "진행 단계" });
    check("[1단계] 등록 직후 3~5단계는 잠김", (await nav.locator('[aria-disabled="true"]').count()) === 3);
    // 하단 이동 줄: 왼쪽 "이전", 오른쪽 "다음"이 같은 줄에 있다.
    const back = page.getByTestId("back-button");
    const next = page.getByTestId("next-button");
    const backBox = await back.boundingBox();
    const nextBox = await next.boundingBox();
    check("[1단계] 이전 버튼 문구는 '이전'", (await back.innerText()).trim() === "이전");
    check("[1단계] 다음 버튼 문구는 '다음'", (await next.innerText()).trim() === "다음");
    check("[1단계] 이전(왼쪽)·다음(오른쪽)이 같은 줄에 배치", backBox.x < nextBox.x && Math.abs(backBox.y - nextBox.y) < 8, JSON.stringify([backBox, nextBox]));
    check("[1단계] 현재 단계 표시(aria-current)", (await nav.locator('[aria-current="step"]').count()) === 1);
  });

  await step("1단계 잠긴 단계 직접 접근", async () => {
    await page.goto(`${WEB}/specs/${specId}/client`);
    await page.getByTestId("step-locked").waitFor({ timeout: 20000 });
    check("[1단계] 분석 전 3단계 URL 직접 접근 시 잠김 안내", true);
    await page.goto(`${WEB}/specs/${specId}/samples`);
    await page.getByTestId("step-locked").waitFor({ timeout: 20000 });
    check("[1단계] 분석 전 4단계 URL 직접 접근 시 잠김 안내", true);
  });

  await step("1단계 파일 업로드(정상)", async () => {
    await openUploadHome();
    await page.getByRole("tab", { name: "파일 업로드" }).click();
    await page.getByTestId("spec-file-input").setInputFiles(`${SAMPLES}/legacy-v2-sample.json`);
    await page.waitForURL(/\/specs\/\d+\/upload/, { timeout: 30000 });
    const count = await page.locator("h2, [data-slot='card-title']").filter({ hasText: /엔드포인트 \d+개/ }).first().innerText();
    check("[1단계] Swagger 2.0(JSON) 파일 업로드 후 변환·등록", /엔드포인트 [1-9]\d*개/.test(count), count);
  });

  await step("1단계 파일 업로드(오류)", async () => {
    await openUploadHome();
    await page.getByRole("tab", { name: "파일 업로드" }).click();
    await page.getByTestId("spec-file-input").setInputFiles(`${SAMPLES}/invalid-sample.yaml`);
    await alertWith("명세를 등록하지 못했습니다").waitFor({ timeout: 20000 });
    check("[1단계] 잘못된 명세 파일은 오류 알림 표시", true);
    check("[1단계] 오류 후에도 업로드 화면 유지", /\/$/.test(page.url()) || !/\/specs\//.test(page.url()), page.url());

    await page.getByTestId("spec-file-input").setInputFiles({ name: "memo.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
    await alertWith("json, yaml, yml 파일만").waitFor({ timeout: 5000 });
    check("[1단계] 허용되지 않는 확장자는 즉시 거부", true);
  });

  await step("1단계 URL 입력 검증", async () => {
    await openUploadHome();
    await page.getByRole("tab", { name: "URL 입력" }).click();
    const input = page.getByLabel("Swagger 명세 URL");
    await input.fill("ftp://example.com/spec.json");
    await page.getByRole("button", { name: "불러오기" }).click();
    await alertWith("http:// 또는 https://").waitFor({ timeout: 5000 });
    check("[1단계] http(s)가 아닌 URL은 입력 단계에서 거부", true);

    await input.fill("http://localhost:8090/mock/spec.json");
    await page.getByRole("button", { name: "불러오기" }).click();
    await alertWith("명세를 등록하지 못했습니다").waitFor({ timeout: 20000 });
    check("[1단계] 내부망(localhost) URL은 서버가 거부(SSRF 방어)", true);
  });

  // ───────────── 2단계: AI 명세 분석 ─────────────
  await step("2단계 분석", async () => {
    await page.goto(`${WEB}/specs/${specId}/analysis`);
    await page.getByTestId("start-analysis").waitFor({ timeout: 20000 });
    check("[2단계] 분석 전에는 '분석 시작' 버튼", (await page.getByTestId("start-analysis").innerText()).includes("분석 시작"));

    await page.getByTestId("start-analysis").click();
    await page.getByText("분석 요약").first().waitFor({ timeout: 90000 });
    check("[2단계] 분석 완료 후 보고서 표시", true);
    check("[2단계] 호출 순서·의존관계 섹션 표시", (await page.getByText("호출 순서와 의존관계").count()) > 0);
    check("[2단계] 엔드포인트 분석 섹션 표시", (await page.getByText("엔드포인트 분석").count()) > 0);
    check("[2단계] 완료 후 버튼이 '다시 분석'으로 변경", (await page.getByTestId("start-analysis").innerText()).includes("다시 분석"));
    await page.screenshot({ path: "e2e-output/step2-report.png", fullPage: true });

    const nav = page.getByRole("navigation", { name: "진행 단계" });
    check("[2단계] 분석 후 3단계 잠금 해제(잠긴 단계 2개)", (await nav.locator('[aria-disabled="true"]').count()) === 2);

    await page.reload();
    await page.getByText("분석 요약").first().waitFor({ timeout: 20000 });
    check("[2단계] 새로고침 후에도 저장된 분석 결과 복원", true);
  });

  // ───────────── 3단계: RestClient 생성 ─────────────
  await step("3단계 생성", async () => {
    await page.getByRole("link", { name: "다음", exact: true }).click();
    await page.waitForURL(`**/specs/${specId}/client`);
    const pkg = page.locator("#basePackage");
    await pkg.waitFor({ timeout: 20000 });

    await pkg.fill("Com.Bad");
    await page.getByTestId("generate-client").click();
    await alertWith("소문자").waitFor({ timeout: 5000 });
    check("[3단계] 대문자 패키지명은 입력 단계에서 거부", true);

    await pkg.fill("com.example.class");
    await page.getByTestId("generate-client").click();
    await alertWith("예약어").waitFor({ timeout: 5000 });
    check("[3단계] Java 예약어 패키지명은 거부", true);

    await pkg.fill("com.example.sample");
    await page.getByTestId("generate-client").click();
    await page.getByTestId("compile-success").waitFor({ timeout: 90000 });
    check("[3단계] 코드 생성 후 컴파일 성공 표시", true);
    check("[3단계] 생성된 소스 뷰어 표시", (await page.getByTestId("code-view").innerText()).trim().length > 0);

    const files = page.getByRole("navigation", { name: "생성된 파일" }).getByRole("button");
    const fileCount = await files.count();
    check("[3단계] 생성 파일 목록 표시", fileCount >= 2, String(fileCount));
    const before = await page.getByTestId("code-view").innerText();
    await files.nth(fileCount - 1).click();
    const after = await page.getByTestId("code-view").innerText();
    check("[3단계] 파일 선택 시 소스 전환", before !== after);

    const [download] = await Promise.all([page.waitForEvent("download", { timeout: 20000 }), page.getByTestId("download-client").click()]);
    check("[3단계] ZIP 다운로드", /^rest-client-\d+\.zip$/.test(download.suggestedFilename()), download.suggestedFilename());
    await page.screenshot({ path: "e2e-output/step3-client.png", fullPage: true });

    await page.reload();
    await page.getByTestId("compile-success").waitFor({ timeout: 20000 });
    check("[3단계] 새로고침 후에도 생성 결과 복원", (await pkg.inputValue()) === "com.example.sample");
  });

  // ───────────── 4단계: 샘플 데이터 ─────────────
  await step("4단계 샘플", async () => {
    await page.getByRole("link", { name: "다음", exact: true }).click().catch(() => page.goto(`${WEB}/specs/${specId}/samples`));
    await page.waitForURL(`**/specs/${specId}/samples`);
    await page.getByTestId("generate-samples").waitFor({ timeout: 20000 });

    await page.getByTestId("generate-samples").click();
    await page.getByTestId("valid-count").waitFor({ timeout: 90000 });
    check("[4단계] 샘플 생성 후 '유효 3 / 전체 3'", (await page.getByTestId("valid-count").innerText()).replace(/\s+/g, " ") === "유효 3 / 전체 3");
    check("[4단계] 엔드포인트별 샘플 카드 3개", (await page.locator('[data-testid^="sample-"]').count()) === 3);

    const editor = page.getByLabel("createItem 샘플 JSON");
    const original = JSON.parse(await editor.inputValue());

    // JSON 문법 오류는 서버 요청 없이 즉시 안내
    let putCalls = 0;
    const countPut = (request) => request.method() === "PUT" && request.url().includes("/samples/") && putCalls++;
    page.on("request", countPut);
    await editor.fill("{ invalid json");
    await page.getByTestId("save-createItem").click();
    await alertWith("JSON 형식 오류").waitFor({ timeout: 5000 });
    page.off("request", countPut);
    check("[4단계] JSON 문법 오류는 저장 시 안내", true);
    check("[4단계] JSON 문법 오류는 서버 요청 없이 처리", putCalls === 0, String(putCalls));

    // 명세 위반 편집: 저장은 되지만 검증 실패 + 오류 목록
    const invalid = structuredClone(original);
    invalid.body.price = -10;
    await editor.fill(JSON.stringify(invalid, null, 2));
    await page.getByTestId("save-createItem").click();
    await page.getByTestId("errors-createItem").waitFor({ timeout: 20000 });
    check("[4단계] 명세를 위반한 샘플은 오류 목록 표시", (await page.getByTestId("errors-createItem").innerText()).includes("price"));
    check("[4단계] 위반 편집 후 유효 개수 2", (await page.getByTestId("valid-count").innerText()).includes("유효 2"));

    // 재생성하면 다시 유효
    await page.getByTestId("regenerate-createItem").click();
    await page.waitForFunction(() => document.querySelector('[data-testid="valid-count"]')?.textContent?.includes("유효 3"), null, { timeout: 30000 });
    check("[4단계] 재생성으로 유효 3 복구", true);

    // 유효한 편집은 직접 편집 출처로 저장
    const valid = JSON.parse(await page.getByLabel("createItem 샘플 JSON").inputValue());
    valid.body.name = "무선 키보드";
    await page.getByLabel("createItem 샘플 JSON").fill(JSON.stringify(valid, null, 2));
    await page.getByTestId("save-createItem").click();
    await page.getByTestId("sample-createItem").getByText("직접 편집").waitFor({ timeout: 20000 });
    check("[4단계] 유효한 편집은 '직접 편집'으로 저장", true);
    await page.screenshot({ path: "e2e-output/step4-samples.png", fullPage: true });

    await page.reload();
    await page.getByTestId("valid-count").waitFor({ timeout: 20000 });
    check("[4단계] 새로고침 후에도 편집 내용 유지", (await page.getByLabel("createItem 샘플 JSON").inputValue()).includes("무선 키보드"));

    const nav = page.getByRole("navigation", { name: "진행 단계" });
    check("[4단계] 샘플 생성 후 5단계 잠금 해제", (await nav.locator('[aria-disabled="true"]').count()) === 0);
    await page.getByRole("link", { name: "다음", exact: true }).click();
    await page.waitForURL(`**/specs/${specId}/test`);
    check("[4단계] 5단계로 이동", true);
  });

  check("콘솔 오류 없음", consoleErrors.filter((e) => !/status of (400|404|409|422)/.test(e)).length === 0, consoleErrors.join(" | "));
} catch (e) {
  failed++;
  results.push("FAIL 예외 :: " + e.message);
  await page.screenshot({ path: "e2e-output/step1-4-error.png", fullPage: true }).catch(() => {});
} finally {
  await browser.close();
}

console.log(results.join("\n"));
console.log(`\n합계: ${results.length}건, 실패 ${failed}건`);
process.exit(failed ? 1 : 0);
