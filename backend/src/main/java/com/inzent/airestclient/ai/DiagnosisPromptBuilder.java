package com.inzent.airestclient.ai;

import com.inzent.airestclient.ai.AiClient.DiagnosisInput;
import com.inzent.airestclient.ai.AiClient.FailureInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** 원인 분석 프롬프트 조립. 서버 응답은 비신뢰 입력이므로 길이를 제한하고 태그 탈출을 막는다. */
public final class DiagnosisPromptBuilder {

  private static final int MAX_FIELD = 600;

  private DiagnosisPromptBuilder() {}

  public static String systemPrompt() {
    try (InputStream in = new ClassPathResource("prompts/diagnosis-system.txt").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("원인 분석 시스템 프롬프트를 읽지 못했습니다.", e);
    }
  }

  public static String userPrompt(DiagnosisInput input) {
    StringBuilder sb = new StringBuilder();
    sb.append("API: ").append(AnalysisPromptBuilder.clean(input.title())).append("\n\n<test_failures>\n");
    for (FailureInfo f : input.failures()) {
      sb.append("## ").append(f.operationId()).append(" — ").append(f.method()).append(' ').append(f.path()).append('\n');
      sb.append("상태 코드: ").append(f.statusCode() == null ? "(응답 없음)" : f.statusCode()).append('\n');
      sb.append("실패 사유: ").append(field(f.failureReason())).append('\n');
      sb.append("요청 샘플: ").append(field(f.requestJson())).append('\n');
      sb.append("응답 본문(일부): ").append(field(f.responseSnippet())).append("\n\n");
    }
    sb.append("</test_failures>\n\n실패한 각 케이스의 원인과 해결 방법을 지정된 JSON 구조로 답하세요.");
    return sb.toString();
  }

  /** 줄바꿈 제거, 길이 제한, 닫는 태그 무력화 */
  private static String field(String text) {
    if (text == null || text.isBlank()) {
      return "(없음)";
    }
    String oneLine = text.replaceAll("\\s+", " ").replace("</", "<\\/").trim();
    return oneLine.length() > MAX_FIELD ? oneLine.substring(0, MAX_FIELD) + "…" : oneLine;
  }
}
