package com.inzent.airestclient.ai;

import com.inzent.airestclient.ai.AiClient.SpecAnalysisInput;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.ParamInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

/** 분석 프롬프트 조립. 명세 텍스트는 비신뢰 입력이므로 길이를 제한하고 줄바꿈을 제거해 <api_spec> 안에 넣는다. */
public final class AnalysisPromptBuilder {

  private static final int MAX_TEXT = 160;

  private AnalysisPromptBuilder() {}

  public static String systemPrompt() {
    try (InputStream in = new ClassPathResource("prompts/analysis-system.txt").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("분석 시스템 프롬프트를 읽지 못했습니다.", e);
    }
  }

  public static String userPrompt(SpecAnalysisInput input) {
    StringBuilder sb = new StringBuilder();
    sb.append("<api_spec>\n");
    sb.append("제목: ").append(clean(input.title())).append('\n');
    if (input.description() != null && !input.description().isBlank()) {
      sb.append("설명: ").append(clean(input.description())).append('\n');
    }
    sb.append("서버: ").append(input.servers().isEmpty() ? "(없음)" : String.join(", ", input.servers())).append('\n');
    sb.append("인증 스킴: ");
    sb.append(input.securitySchemes().isEmpty() ? "(없음)"
        : input.securitySchemes().stream()
            .map(s -> clean(s.name()) + "(" + s.type() + (s.keyName() != null ? ":" + s.keyName() : "") + ")")
            .collect(Collectors.joining(", ")));
    sb.append("\n\n엔드포인트 (operationId | 메서드 경로 | 요약 | 인증 | 파라미터 | 요청본문 | 응답코드):\n");
    for (EndpointInfo ep : input.endpoints()) {
      sb.append(ep.operationId()).append(" | ")
          .append(ep.method()).append(' ').append(ep.path()).append(" | ")
          .append(clean(ep.summary())).append(" | ")
          .append(ep.secured() ? "필요" : "공개").append(" | ")
          .append(ep.parameters().isEmpty() ? "-" : ep.parameters().stream().map(AnalysisPromptBuilder::param).collect(Collectors.joining(",")))
          .append(" | ")
          .append(ep.hasRequestBody() ? (ep.requestBodyRequired() ? "필수" : "선택") : "-")
          .append(" | ")
          .append(String.join(",", ep.responseCodes()))
          .append('\n');
    }
    sb.append("</api_spec>\n\n위 명세를 분석해 지정된 JSON 구조로 답하세요.");
    return sb.toString();
  }

  private static String param(ParamInfo p) {
    return p.name() + ":" + p.in() + (p.required() ? "*" : "");
  }

  /** 줄바꿈 제거 + 길이 제한. 태그 탈출(</api_spec>)을 막기 위해 꺾쇠도 치환한다. */
  static String clean(String text) {
    if (text == null) {
      return "";
    }
    String oneLine = text.replaceAll("\\s+", " ").replace('<', '(').replace('>', ')').trim();
    return oneLine.length() > MAX_TEXT ? oneLine.substring(0, MAX_TEXT) + "…" : oneLine;
  }
}
