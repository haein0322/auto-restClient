package com.inzent.airestclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.AuthAnalysis;
import com.inzent.airestclient.dto.AnalysisDtos.DependencyInfo;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.SecuritySchemeInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AI 응답 검증기: AI가 틀리거나 악의적인 값을 줘도 저장되는 결과는 안전해야 한다. */
class AnalysisNormalizerTest {

  private final AnalysisNormalizer normalizer = new AnalysisNormalizer();

  private static EndpointInfo ep(String id, String method, String path, boolean secured) {
    return new EndpointInfo(id, method, path, id + " 요약", "tag", secured, List.of(), false, false, List.of("200"));
  }

  private static EndpointInsight insight(String id, String name, RiskLevel risk) {
    return new EndpointInsight(id, name, id + " 목적", risk, "");
  }

  private static AiAnalysisOutput output(List<EndpointInsight> insights, List<DependencyInfo> deps) {
    return new AiAnalysisOutput("요약", new AuthAnalysis(false, "", "인증 설명"), insights, deps, List.of());
  }

  private AnalysisReport normalize(AiAnalysisOutput ai, List<EndpointInfo> endpoints) {
    return normalizer.normalize(ai, endpoints, List.of(new SecuritySchemeInfo("ApiKeyAuth", "APIKEY", "HEADER", "X-API-KEY")),
        "test", "test-model");
  }

  private static final List<EndpointInfo> SHOP = List.of(
      ep("listProducts", "GET", "/products", false),
      ep("createProduct", "POST", "/products", true),
      ep("getProduct", "GET", "/products/{id}", false),
      ep("deleteProduct", "DELETE", "/products/{id}", true));

  @Test
  void 존재하지_않는_operationId는_제거되고_경고한다() {
    AnalysisReport report = normalize(output(List.of(
        insight("listProducts", "listProducts", RiskLevel.SAFE),
        insight("hackedOperation", "hack", RiskLevel.SAFE)), List.of()), SHOP);

    assertThat(report.endpoints()).extracting(EndpointInsight::operationId)
        .containsExactly("listProducts", "createProduct", "getProduct", "deleteProduct")
        .doesNotContain("hackedOperation");
    assertThat(report.warnings()).anyMatch(w -> w.contains("명세에 없는 operationId 1개"));
  }

  @Test
  void AI가_누락한_엔드포인트는_기본값으로_채운다() {
    AnalysisReport report = normalize(output(List.of(), List.of()), SHOP);

    assertThat(report.endpoints()).hasSize(4);
    assertThat(report.endpoints()).allSatisfy(i -> assertThat(i.suggestedMethodName()).isNotBlank());
    assertThat(report.warnings()).anyMatch(w -> w.contains("누락한 엔드포인트 4개"));
  }

  @Test
  void 위험도는_메서드_기준_하한_아래로_내려가지_않는다() {
    AnalysisReport report = normalize(output(List.of(
        insight("deleteProduct", "deleteProduct", RiskLevel.SAFE),
        insight("createProduct", "createProduct", RiskLevel.SAFE),
        insight("listProducts", "listProducts", RiskLevel.DESTRUCTIVE)), List.of()), SHOP);

    assertThat(risk(report, "deleteProduct")).isEqualTo(RiskLevel.DESTRUCTIVE);
    assertThat(risk(report, "createProduct")).isEqualTo(RiskLevel.MUTATING);
    // 하한보다 높게 판단한 것은 존중한다(보수적 방향)
    assertThat(risk(report, "listProducts")).isEqualTo(RiskLevel.DESTRUCTIVE);
    assertThat(report.warnings()).anyMatch(w -> w.contains("deleteProduct") && w.contains("상향"));
  }

  private static RiskLevel risk(AnalysisReport report, String id) {
    return report.endpoints().stream().filter(i -> i.operationId().equals(id)).findFirst().orElseThrow().risk();
  }

  @Test
  void 유효하지_않은_메서드명은_대체하고_중복은_번호를_붙인다() {
    List<EndpointInfo> endpoints = List.of(
        ep("get_items_id", "GET", "/items/{id}", false),
        ep("class", "GET", "/classes", false),
        ep("dup-a", "GET", "/a", false),
        ep("dup-b", "GET", "/b", false));
    AnalysisReport report = normalize(output(List.of(
        insight("get_items_id", "Get Items; DROP TABLE", RiskLevel.SAFE),
        insight("class", "class", RiskLevel.SAFE),
        insight("dup-a", "fetchData", RiskLevel.SAFE),
        insight("dup-b", "fetchData", RiskLevel.SAFE)), List.of()), endpoints);

    List<String> names = report.endpoints().stream().map(EndpointInsight::suggestedMethodName).toList();
    assertThat(names).containsExactly("getItemsId", "classCall", "fetchData", "fetchData2");
    assertThat(names).doesNotHaveDuplicates();
    assertThat(names).allMatch(n -> n.matches("^[a-z][A-Za-z0-9]*$"));
  }

  @Test
  void 유효하지_않은_의존관계는_제거되고_실행순서는_서버가_계산한다() {
    AnalysisReport report = normalize(output(List.of(), List.of(
        new DependencyInfo("createProduct", "getProduct", "생성 후 조회"),
        new DependencyInfo("createProduct", "getProduct", "중복"),
        new DependencyInfo("createProduct", "nothing", "없는 대상"),
        new DependencyInfo("getProduct", "getProduct", "자기 자신"))), SHOP);

    assertThat(report.dependencies()).hasSize(1);
    assertThat(report.warnings()).anyMatch(w -> w.contains("의존관계 3건"));
    // 생성 → 조회 → 삭제 순서, 삭제는 항상 마지막
    assertThat(report.testOrder()).containsExactly("createProduct", "listProducts", "getProduct", "deleteProduct");
  }

  @Test
  void 의존관계가_기본순서를_거스르면_의존관계가_우선한다() {
    // 기본 순서로는 listProducts(조회)가 getProduct보다 앞이지만, getProduct → listProducts 의존이 있으면 뒤로 간다.
    AnalysisReport report = normalize(output(List.of(), List.of(
        new DependencyInfo("getProduct", "listProducts", "테스트용"))), SHOP);

    List<String> order = report.testOrder();
    assertThat(order.indexOf("getProduct")).isLessThan(order.indexOf("listProducts"));
    assertThat(order).containsExactlyInAnyOrder("createProduct", "listProducts", "getProduct", "deleteProduct");
  }

  @Test
  void 순환_의존은_경고하고_모든_항목을_포함한다() {
    AnalysisReport report = normalize(output(List.of(), List.of(
        new DependencyInfo("listProducts", "getProduct", "a"),
        new DependencyInfo("getProduct", "listProducts", "b"))), SHOP);

    assertThat(report.warnings()).anyMatch(w -> w.contains("순환"));
    assertThat(report.testOrder()).containsExactlyInAnyOrder("createProduct", "listProducts", "getProduct", "deleteProduct");
  }

  @Test
  void 인증_필요_여부는_AI가_아니라_명세에서_계산한다() {
    // AI는 required=false라고 했지만 인증이 필요한 엔드포인트가 있으므로 true여야 한다.
    AnalysisReport report = normalize(output(List.of(), List.of()), SHOP);

    assertThat(report.authentication().required()).isTrue();
    assertThat(report.authentication().scheme()).isEqualTo("ApiKeyAuth");
    assertThat(report.authentication().description()).isEqualTo("인증 설명");
  }

  @Test
  void 긴_텍스트는_잘리고_주의사항은_최대_10개다() {
    String longText = "가".repeat(5000);
    List<String> cautions = java.util.stream.IntStream.range(0, 30).mapToObj(i -> "주의 " + i).toList();
    AiAnalysisOutput ai = new AiAnalysisOutput(longText, new AuthAnalysis(true, "", longText),
        List.of(new EndpointInsight("listProducts", "listProducts", longText, RiskLevel.SAFE, longText)),
        List.of(), cautions);

    AnalysisReport report = normalize(ai, SHOP);

    assertThat(report.summary().length()).isLessThanOrEqualTo(1001);
    assertThat(report.authentication().description().length()).isLessThanOrEqualTo(301);
    assertThat(report.endpoints().get(0).purpose().length()).isLessThanOrEqualTo(301);
    assertThat(report.cautions()).hasSize(10);
  }

  @Test
  void null_응답도_기본값으로_안전하게_처리한다() {
    AnalysisReport report = normalizer.normalize(null, SHOP, List.of(), "test", "m");

    assertThat(report.endpoints()).hasSize(4);
    assertThat(report.testOrder()).hasSize(4);
    assertThat(report.summary()).isNotBlank();
  }

  @Test
  void camelCase_변환() {
    assertThat(AnalysisNormalizer.toCamelCase("get_items_id")).isEqualTo("getItemsId");
    assertThat(AnalysisNormalizer.toCamelCase("list-products")).isEqualTo("listProducts");
    assertThat(AnalysisNormalizer.toCamelCase("123abc")).isEqualTo("call123abc");
    assertThat(AnalysisNormalizer.toCamelCase("한글만")).isEqualTo("call");
    assertThat(AnalysisNormalizer.toCamelCase("new")).isEqualTo("newCall");
  }
}
