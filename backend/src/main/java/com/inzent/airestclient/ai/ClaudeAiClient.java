package com.inzent.airestclient.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.SampleDtos.AiSampleOutput;
import com.inzent.airestclient.dto.TestDtos.AiDiagnosisOutput;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Anthropic 공식 Java SDK로 Claude를 호출하는 구현.
 * 구조화 출력(output_config.format)으로 {@link AiAnalysisOutput} 스키마를 강제한다.
 * (Opus 5.5는 강제 tool_choice를 지원하지 않으므로 tool use 대신 구조화 출력을 사용한다.)
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "claude")
public class ClaudeAiClient implements AiClient {

  private static final Logger log = LoggerFactory.getLogger(ClaudeAiClient.class);

  private final AnthropicClient client;
  private final String model;
  private final long maxTokens;

  public ClaudeAiClient(
      @Value("${app.ai.claude.api-key:}") String apiKey,
      @Value("${app.ai.claude.model:claude-opus-5-5}") String model,
      @Value("${app.ai.claude.base-url:}") String baseUrl,
      @Value("${app.ai.claude.timeout-seconds:180}") long timeoutSeconds,
      @Value("${app.ai.claude.max-tokens:16000}") long maxTokens) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "app.ai.provider=claude 이지만 API 키가 없습니다. ANTHROPIC_API_KEY 환경변수를 설정하거나 "
              + "app.ai.provider=fake 로 실행하세요.");
    }
    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(timeoutSeconds));
    if (baseUrl != null && !baseUrl.isBlank()) {
      builder.baseUrl(baseUrl);
    }
    this.client = builder.build();
    this.model = model;
    this.maxTokens = maxTokens;
  }

  @Override
  public String provider() {
    return "claude";
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public AiAnalysisOutput analyze(SpecAnalysisInput input) {
    return callStructured(AnalysisPromptBuilder.systemPrompt(), AnalysisPromptBuilder.userPrompt(input),
        AiAnalysisOutput.class);
  }

  @Override
  public AiSampleOutput generateSamples(SampleGenerationInput input) {
    return callStructured(SamplePromptBuilder.systemPrompt(), SamplePromptBuilder.userPrompt(input),
        AiSampleOutput.class);
  }

  @Override
  public AiDiagnosisOutput diagnose(DiagnosisInput input) {
    return callStructured(DiagnosisPromptBuilder.systemPrompt(), DiagnosisPromptBuilder.userPrompt(input),
        AiDiagnosisOutput.class);
  }

  /** 구조화 출력으로 호출하고, 오류·거부·잘림을 AI_FAILED 예외로 일관되게 변환한다. */
  private <T> T callStructured(String systemPrompt, String userPrompt, Class<T> outputType) {
    StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .system(systemPrompt)
        .outputConfig(outputType)
        .addUserMessage(userPrompt)
        .build();

    StructuredMessage<T> response;
    try {
      response = client.messages().create(params);
    } catch (AnthropicServiceException e) {
      // 응답 본문에는 요청 내용이 포함될 수 있으므로 상태 코드만 사용자에게 노출한다.
      log.warn("Claude API 오류: status={}, type={}", e.statusCode(), e.getClass().getSimpleName(), e);
      throw new BusinessException(ErrorCode.AI_FAILED,
          "Claude API 호출이 실패했습니다(HTTP " + e.statusCode() + "). 잠시 후 다시 시도하세요.", e);
    } catch (AnthropicException e) {
      log.warn("Claude 응답 처리 오류", e);
      throw new BusinessException(ErrorCode.AI_FAILED,
          "Claude 응답을 처리하지 못했습니다. 잠시 후 다시 시도하세요.", e);
    }

    if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
      throw new BusinessException(ErrorCode.AI_FAILED, "Claude가 안전 정책에 따라 요청을 거부했습니다.");
    }
    if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
      throw new BusinessException(ErrorCode.AI_FAILED,
          "응답이 중간에 끊겼습니다. 엔드포인트 수를 줄여 다시 시도하세요.");
    }

    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(typed -> typed.text())
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.AI_FAILED, "Claude 응답에 결과가 없습니다."));
  }
}
