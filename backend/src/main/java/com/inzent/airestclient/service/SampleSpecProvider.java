package com.inzent.airestclient.service;

import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import com.inzent.airestclient.dto.SpecDtos.SampleInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 클래스패스(samples/)에 포함된 내장 샘플 명세를 제공한다. 허용 목록 기반이라 경로 조작이 불가능하다. */
@Component
public class SampleSpecProvider {

  private record Sample(String fileName, SampleInfo info) {}

  private final Map<String, Sample> samples = new LinkedHashMap<>();

  public SampleSpecProvider() {
    register("shop-sample", "shop-sample.yaml", "쇼핑몰 샘플 API (OpenAPI 3.0)",
        "상품·고객·주문 12개 엔드포인트. 내장 Mock 서버와 연동되어 통신 테스트까지 가능합니다.");
    register("legacy-v2-sample", "legacy-v2-sample.json", "레거시 메모 API (Swagger 2.0)",
        "Swagger 2.0 파싱 호환성 확인용 소형 샘플입니다.");
    register("invalid-sample", "invalid-sample.yaml", "오류 케이스 샘플",
        "의도적으로 잘못된 명세입니다. 업로드 오류 표시를 확인할 때 사용하세요.");
  }

  private void register(String name, String fileName, String title, String description) {
    samples.put(name, new Sample(fileName, new SampleInfo(name, title, description)));
  }

  public List<SampleInfo> list() {
    return samples.values().stream().map(Sample::info).toList();
  }

  public String load(String name) {
    Sample sample = samples.get(name);
    if (sample == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 샘플입니다: " + name);
    }
    try (InputStream in = new ClassPathResource("samples/" + sample.fileName()).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "샘플 파일을 읽지 못했습니다.", e);
    }
  }
}
