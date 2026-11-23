package com.inzent.airestclient.ai;

import com.inzent.airestclient.ai.AiClient.SampleGenerationInput;
import com.inzent.airestclient.ai.AiClient.SampleTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** 샘플 생성 프롬프트 조립. 스키마 JSON은 비신뢰 입력이므로 태그 탈출을 막고, 검증 오류는 별도 태그로 전달한다. */
public final class SamplePromptBuilder {

  private static final int MAX_ERRORS_PER_ENDPOINT = 8;
  private static final int MAX_ERROR_LENGTH = 300;

  private SamplePromptBuilder() {}

  public static String systemPrompt() {
    try (InputStream in = new ClassPathResource("prompts/samples-system.txt").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("샘플 생성 시스템 프롬프트를 읽지 못했습니다.", e);
    }
  }

  public static String userPrompt(SampleGenerationInput input) {
    StringBuilder sb = new StringBuilder();
    sb.append("API: ").append(AnalysisPromptBuilder.clean(input.title())).append("\n\n");
    sb.append("<api_spec>\n");
    for (SampleTarget target : input.targets()) {
      sb.append("## ").append(target.operationId()).append(" — ").append(target.method()).append(' ')
          .append(target.path()).append('\n');
      sb.append(neutralize(target.schemaJson())).append("\n\n");
    }
    sb.append("</api_spec>\n");

    Map<String, List<String>> feedback = input.feedback();
    if (feedback != null && !feedback.isEmpty()) {
      sb.append("\n<validation_errors>\n");
      feedback.forEach((operationId, errors) -> {
        sb.append(operationId).append(":\n");
        errors.stream().limit(MAX_ERRORS_PER_ENDPOINT)
            .forEach(e -> sb.append("- ").append(truncate(neutralize(e))).append('\n'));
      });
      sb.append("</validation_errors>\n");
    }
    sb.append("\n위 엔드포인트 각각에 대한 샘플 요청을 지정된 JSON 구조로 답하세요.");
    return sb.toString();
  }

  /** 입력 속의 태그를 무력화해 <api_spec> / <validation_errors> 구간을 벗어나지 못하게 한다. */
  private static String neutralize(String text) {
    return text == null ? "" : text.replace("</", "<\\/");
  }

  private static String truncate(String text) {
    String oneLine = text.replaceAll("\\s+", " ");
    return oneLine.length() > MAX_ERROR_LENGTH ? oneLine.substring(0, MAX_ERROR_LENGTH) + "…" : oneLine;
  }
}
