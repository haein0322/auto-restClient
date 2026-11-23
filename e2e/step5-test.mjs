import { chromium } from "playwright";

const API = process.env.E2E_API_URL ?? "http://localhost:8090";
const WEB = process.env.E2E_WEB_URL ?? "http://localhost:3000";
const results = [];
let failed = 0;

function check(name, ok, extra = "") {
  results.push(`${ok ? "PASS" : "FAIL"} ${name}${ok ? "" : " :: " + extra}`);
  if (!ok) failed++;
}

async function post(path, body) {
  const res = await fetch(API + path, { method: "POST", ...(body ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {}) });
  return res;
}

async function readySpec() {
  const reg = await (await post("/api/specs/samples/shop-sample")).json();
  const id = reg.data.id;
  await (await post(`/api/specs/${id}/analysis`)).text(); // SSE를 끝까지 소비
  const client = await post(`/api/specs/${id}/client`);
  if (client.status !== 201) throw new Error("client " + client.status);
  const samples = await post(`/api/specs/${id}/samples`);
  if (samples.status !== 201) throw new Error("samples " + samples.status);
  return id;
}

import { mkdirSync } from "node:fs";
mkdirSync("e2e-output", { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const consoleErrors = [];
page.on("console", (m) => m.type() === "error" && consoleErrors.push(m.text()));
page.on("pageerror", (e) => consoleErrors.push(String(e)));

try {
  // 1) 잠긴 단계: 명세만 등록한 상태에서는 5단계가 막혀야 한다
  const reg = await (await post("/api/specs/samples/shop-sample")).json();
  await page.goto(`${WEB}/specs/${reg.data.id}/test`);
  await page.getByTestId("step-locked").waitFor({ timeout: 20000 });
  check("명세만 등록하면 5단계는 잠김", true);

  // 2) 준비된 명세: 기본값이 채워진다
  const id = await readySpec();
  await page.goto(`${WEB}/specs/${id}/test`);
  await page.getByTestId("test-base-url").waitFor({ timeout: 20000 });
  const baseUrl = await page.getByTestId("test-base-url").inputValue();
  const apiKey = await page.getByTestId("test-api-key").inputValue();
  check("기본 Base URL이 채워짐", baseUrl.startsWith("http"), baseUrl);
  check("내장 Mock 데모 키가 채워짐", apiKey.length > 0, apiKey);

  // 3) 입력 검증: 잘못된 주소는 서버 요청 없이 즉시 안내
  await page.getByTestId("test-base-url").fill("localhost:8090/mock");
  await page.getByTestId("start-test").click();
  await page.getByTestId("test-base-url-error").waitFor({ timeout: 5000 });
  check("잘못된 주소는 즉시 오류 표시", true);

  // 4) 외부 호스트는 확인 없이는 서버가 거부
  await page.getByTestId("test-base-url").fill("http://example.org/api");
  await page.getByTestId("start-test").click();
  await page.getByText("외부 서버 호출 확인").first().waitFor({ timeout: 10000 });
  const alertText = await page.getByRole("alert").filter({ hasText: "외부 서버 호출 확인" }).first().innerText();
  check("외부 호스트는 확인 없이 거부", alertText.includes("외부 서버 호출 확인"), alertText);

  // 5) 정상 실행(삭제 호출 미허용): 통과 + 건너뜀, 실패 0
  await page.getByTestId("test-base-url").fill(baseUrl);
  await page.getByTestId("start-test").click();
  await page.getByTestId("count-passed").waitFor({ timeout: 90000 });
  const passed1 = await page.getByTestId("count-passed").innerText();
  const failed1 = await page.getByTestId("count-failed").innerText();
  const skipped1 = await page.getByTestId("count-skipped").innerText();
  check("삭제 미허용 실행: 실패 0", failed1.includes("0"), failed1);
  check("삭제 미허용 실행: 건너뜀 존재", !skipped1.includes(" 0"), skipped1);
  check("통과 건수 표시", /통과 [1-9]/.test(passed1), passed1);
  check("결과 표 행 12개", (await page.locator('[data-testid^="case-"]').count()) === 12);

  // 6) 행 펼침: 요청/응답 상세
  await page.getByTestId("case-createProduct").click();
  await page.getByTestId("detail-createProduct").waitFor({ timeout: 5000 });
  const detail = await page.getByTestId("detail-createProduct").innerText();
  check("펼치면 요청·응답 상세 표시", detail.includes("전송한 요청") && detail.includes("응답 본문"));

  // 7) 삭제 허용 + 반복 실행: 전부 통과
  await page.getByTestId("test-allow-destructive").check();
  await page.getByTestId("start-test").click();
  await page.waitForFunction(() => document.querySelector('[data-testid="count-passed"]')?.textContent?.includes("12"), null, { timeout: 90000 });
  check("삭제 허용 반복 실행: 12건 전부 통과", (await page.getByTestId("count-failed").innerText()).includes("0"));
  check("실행 이력 2건 이상 표시", (await page.getByTestId("run-history").locator("li").count()) >= 2);
  await page.screenshot({ path: "e2e-output/step5-pass.png", fullPage: true });

  // 8) 잘못된 키 → 실패 → 원인 분석
  await page.getByTestId("test-api-key").fill("wrong-key");
  await page.getByTestId("test-allow-destructive").uncheck();
  await page.getByTestId("start-test").click();
  await page.getByTestId("diagnose").waitFor({ timeout: 90000 });
  const failedCount = await page.getByTestId("count-failed").innerText();
  check("잘못된 키: 실패 발생", !failedCount.includes(" 0"), failedCount);
  await page.getByTestId("diagnose").click();
  await page.getByTestId("diagnosis-summary").waitFor({ timeout: 30000 });
  await page.locator('[data-testid^="case-"][data-outcome="FAIL"]').first().click();
  await page.locator('[data-testid^="diagnosis-"]:not([data-testid="diagnosis-summary"])').first().waitFor({ timeout: 5000 });
  check("원인 분석이 실패 행에 표시됨", true);
  await page.screenshot({ path: "e2e-output/step5-fail.png", fullPage: true });

  // 9) 새로고침해도 마지막 결과와 분석이 유지
  await page.reload();
  await page.getByTestId("count-failed").waitFor({ timeout: 20000 });
  check("새로고침 후에도 최근 결과 복원", (await page.getByTestId("diagnosis-summary").count()) === 1);

  // 10) 4단계 → 5단계 이동 링크 및 StepNav 상태
  await page.goto(`${WEB}/specs/${id}/samples`);
  await page.getByRole("link", { name: /다음: 통신 테스트/ }).click();
  await page.waitForURL(`**/specs/${id}/test`);
  check("4단계에서 5단계로 이동", true);

  check("콘솔 오류 없음", consoleErrors.filter((e) => !e.includes("401") && !e.includes("400")).length === 0, consoleErrors.join(" | "));
} catch (e) {
  failed++;
  results.push("FAIL 예외 :: " + e.message);
  await page.screenshot({ path: "e2e-output/step5-error.png", fullPage: true }).catch(() => {});
} finally {
  await browser.close();
}

console.log(results.join("\n"));
console.log(`\n합계: ${results.length}건, 실패 ${failed}건`);
process.exit(failed ? 1 : 0);
