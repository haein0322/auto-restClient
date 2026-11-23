package com.inzent.airestclient.config;

import com.inzent.airestclient.mock.MockAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS(프론트엔드 개발 서버 허용)와 Mock 서버 인터셉터 등록 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;
  private final MockAuthInterceptor mockAuthInterceptor;

  public WebConfig(
      @Value("${app.cors.allowed-origins}") String[] allowedOrigins,
      MockAuthInterceptor mockAuthInterceptor) {
    this.allowedOrigins = allowedOrigins;
    this.mockAuthInterceptor = mockAuthInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*");
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(mockAuthInterceptor).addPathPatterns("/mock/**");
  }
}
