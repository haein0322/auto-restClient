package com.inzent.airestclient.mock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Mock 서버 공통 처리: API Key 인증과 응답 지연(타임아웃 테스트용).
 * - 상품 조회(GET /mock/products...)는 공개 API
 * - 그 외는 X-API-KEY 헤더 필요
 * - X-Mock-Delay-Ms 헤더로 응답 지연 지정(최대 10초)
 */
@Component
public class MockAuthInterceptor implements HandlerInterceptor {

  private static final long MAX_DELAY_MS = 10_000;

  private final String apiKey;

  public MockAuthInterceptor(@Value("${app.mock.api-key:demo-key}") String apiKey) {
    this.apiKey = apiKey;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    applyDelay(request.getHeader("X-Mock-Delay-Ms"));

    if (isPublic(request)) {
      return true;
    }
    String provided = request.getHeader("X-API-KEY");
    if (apiKey.equals(provided)) {
      return true;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(
        "{\"code\":\"UNAUTHORIZED\",\"message\":\"유효한 X-API-KEY 헤더가 필요합니다.\"}");
    return false;
  }

  private boolean isPublic(HttpServletRequest request) {
    return HttpMethod.GET.matches(request.getMethod())
        && request.getRequestURI().startsWith("/mock/products");
  }

  private void applyDelay(String header) {
    if (header == null) {
      return;
    }
    try {
      long delay = Math.min(Long.parseLong(header.trim()), MAX_DELAY_MS);
      if (delay > 0) {
        Thread.sleep(delay);
      }
    } catch (NumberFormatException ignored) {
      // 잘못된 지연 값은 무시한다.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
