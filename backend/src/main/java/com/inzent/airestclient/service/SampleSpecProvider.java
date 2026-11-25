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

  /** @param listed false이면 화면 목록에는 나오지 않지만 이름으로는 불러올 수 있다(자동 테스트용 명세). */
  private record Sample(String fileName, SampleInfo info, boolean listed) {}

  private final Map<String, Sample> samples = new LinkedHashMap<>();

  public SampleSpecProvider() {
    register("sample-api", "sample-api.yaml", "샘플 API",
        "엔드포인트 3개로 구성된 샘플입니다. 내장 Mock 서버와 연동되어 통신 테스트까지 해볼 수 있습니다.", true);
    // 아래 명세는 자동 테스트(회귀·호환성·오류 처리)에서만 사용하므로 목록에 표시하지 않는다.
    register("shop-sample", "shop-sample.yaml", "테스트용 대형 명세", "자동 테스트 전용", false);
    register("legacy-v2-sample", "legacy-v2-sample.json", "테스트용 Swagger 2.0 명세", "자동 테스트 전용", false);
    register("invalid-sample", "invalid-sample.yaml", "테스트용 오류 명세", "자동 테스트 전용", false);
  }

  private void register(String name, String fileName, String title, String description, boolean listed) {
    samples.put(name, new Sample(fileName, new SampleInfo(name, title, description), listed));
  }

  public List<SampleInfo> list() {
    return samples.values().stream().filter(Sample::listed).map(Sample::info).toList();
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
