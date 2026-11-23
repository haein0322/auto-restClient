package com.inzent.airestclient.service;

import com.inzent.airestclient.dto.AnalysisDtos.AiAnalysisOutput;
import com.inzent.airestclient.dto.AnalysisDtos.AnalysisReport;
import com.inzent.airestclient.dto.AnalysisDtos.AuthAnalysis;
import com.inzent.airestclient.dto.AnalysisDtos.DependencyInfo;
import com.inzent.airestclient.dto.AnalysisDtos.EndpointInsight;
import com.inzent.airestclient.dto.AnalysisDtos.RiskLevel;
import com.inzent.airestclient.dto.SpecDtos.EndpointInfo;
import com.inzent.airestclient.dto.SpecDtos.SecuritySchemeInfo;
import com.inzent.airestclient.generator.JavaNaming;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * AI 응답을 신뢰하지 않고 검증·보정한다(환각, 프롬프트 인젝션, 형식 오류 방어).
 * 이 단계를 통과한 결과만 저장되고 이후 코드 생성·통신 테스트에서 사용된다.
 *
 * <ul>
 *   <li>존재하지 않는 operationId는 제거, 누락된 엔드포인트는 기본값으로 채움</li>
 *   <li>위험도는 HTTP 메서드 기준 하한 적용(AI가 DELETE를 SAFE로 판단해도 DESTRUCTIVE 유지)</li>
 *   <li>메서드명은 유효한 Java 식별자·유일성 보장</li>
 *   <li>의존관계는 검증 후 서버가 위상 정렬로 실행 순서를 직접 계산(AI의 순서는 사용하지 않음)</li>
 *   <li>인증 필요 여부는 명세에서 결정적으로 계산</li>
 * </ul>
 */
@Component
public class AnalysisNormalizer {

  private static final Pattern CAMEL = Pattern.compile("^[a-z][A-Za-z0-9]*$");
  private static final int MAX_SUMMARY = 1000;
  private static final int MAX_LINE = 300;
  private static final int MAX_CAUTIONS = 10;

  public AnalysisReport normalize(AiAnalysisOutput raw, List<EndpointInfo> endpoints,
      List<SecuritySchemeInfo> securitySchemes, String provider, String model) {
    List<String> warnings = new ArrayList<>();
    AiAnalysisOutput ai = raw == null
        ? new AiAnalysisOutput(null, null, List.of(), List.of(), List.of()) : raw;

    List<EndpointInsight> insights = normalizeInsights(ai.endpoints(), endpoints, warnings);
    Set<String> known = new HashSet<>();
    endpoints.forEach(ep -> known.add(ep.operationId()));
    List<DependencyInfo> dependencies = normalizeDependencies(ai.dependencies(), known, warnings);
    List<String> testOrder = computeTestOrder(endpoints, dependencies, warnings);

    return new AnalysisReport(provider, model,
        truncate(orDefault(ai.summary(), "(AI가 요약을 제공하지 않았습니다)"), MAX_SUMMARY),
        normalizeAuth(ai.authentication(), endpoints, securitySchemes),
        insights, dependencies, testOrder, normalizeCautions(ai.cautions()), warnings);
  }

  // ---------------- 엔드포인트별 해석 ----------------

  private List<EndpointInsight> normalizeInsights(List<EndpointInsight> aiInsights,
      List<EndpointInfo> endpoints, List<String> warnings) {
    Map<String, EndpointInsight> byId = new LinkedHashMap<>();
    int unknown = 0;
    int duplicated = 0;
    for (EndpointInsight insight : aiInsights == null ? List.<EndpointInsight>of() : aiInsights) {
      if (insight == null || insight.operationId() == null) {
        continue;
      }
      boolean exists = endpoints.stream().anyMatch(ep -> ep.operationId().equals(insight.operationId()));
      if (!exists) {
        unknown++;
      } else if (byId.putIfAbsent(insight.operationId(), insight) != null) {
        duplicated++;
      }
    }
    if (unknown > 0) {
      warnings.add("AI가 명세에 없는 operationId " + unknown + "개를 반환해 제외했습니다.");
    }
    if (duplicated > 0) {
      warnings.add("AI가 같은 엔드포인트를 중복 반환해 첫 번째만 사용했습니다(" + duplicated + "건).");
    }

    List<EndpointInsight> result = new ArrayList<>();
    Set<String> usedNames = new HashSet<>();
    int filled = 0;
    for (EndpointInfo ep : endpoints) {
      EndpointInsight ai = byId.get(ep.operationId());
      if (ai == null) {
        filled++;
      }
      RiskLevel floor = riskFloor(ep.method());
      RiskLevel risk = ai == null || ai.risk() == null ? floor : max(ai.risk(), floor);
      if (ai != null && ai.risk() != null && ai.risk().ordinal() < floor.ordinal()) {
        warnings.add(ep.operationId() + ": AI 위험도 " + ai.risk() + "를 " + ep.method()
            + " 메서드 기준 " + floor + "로 상향했습니다.");
      }

      String name = uniqueMethodName(ai == null ? null : ai.suggestedMethodName(), ep, usedNames, warnings);
      String purpose = ai != null && notBlank(ai.purpose()) ? truncate(ai.purpose(), MAX_LINE)
          : (notBlank(ep.summary()) ? truncate(ep.summary(), MAX_LINE) : ep.method() + " " + ep.path());
      String notes = ai == null ? "" : truncate(orDefault(ai.notes(), ""), MAX_LINE);
      result.add(new EndpointInsight(ep.operationId(), name, purpose, risk, notes));
    }
    if (filled > 0) {
      warnings.add("AI가 누락한 엔드포인트 " + filled + "개는 기본값으로 채웠습니다.");
    }
    return result;
  }

  private static RiskLevel riskFloor(String method) {
    return switch (method) {
      case "DELETE" -> RiskLevel.DESTRUCTIVE;
      case "POST", "PUT", "PATCH" -> RiskLevel.MUTATING;
      default -> RiskLevel.SAFE;
    };
  }

  private static RiskLevel max(RiskLevel a, RiskLevel b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  /** 제안된 이름이 유효하고 유일하면 사용, 아니면 operationId에서 안전한 이름을 만든다. */
  private String uniqueMethodName(String suggested, EndpointInfo ep, Set<String> used,
      List<String> warnings) {
    String candidate = suggested != null ? suggested.trim() : null;
    boolean valid = candidate != null && CAMEL.matcher(candidate).matches()
        && !JavaNaming.KEYWORDS.contains(candidate) && candidate.length() <= 60;
    if (!valid) {
      String fallback = JavaNaming.toCamelCase(ep.operationId());
      if (suggested != null) {
        warnings.add(ep.operationId() + ": 메서드명 '" + truncate(suggested, 40)
            + "'이(가) 유효하지 않아 '" + fallback + "'로 대체했습니다.");
      }
      candidate = fallback;
    }
    String unique = candidate;
    int seq = 2;
    while (!used.add(unique)) {
      unique = candidate + seq++;
    }
    if (!unique.equals(candidate)) {
      warnings.add(ep.operationId() + ": 메서드명 '" + candidate + "'이(가) 중복되어 '" + unique + "'로 변경했습니다.");
    }
    return unique;
  }

  /** 하위 호환용 위임(테스트에서 직접 사용). 변환 규칙은 JavaNaming에 있다. */
  static String toCamelCase(String raw) {
    return JavaNaming.toCamelCase(raw);
  }

  // ---------------- 의존관계와 실행 순서 ----------------

  private List<DependencyInfo> normalizeDependencies(List<DependencyInfo> aiDeps, Set<String> known,
      List<String> warnings) {
    List<DependencyInfo> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int dropped = 0;
    for (DependencyInfo dep : aiDeps == null ? List.<DependencyInfo>of() : aiDeps) {
      boolean valid = dep != null && dep.fromOperationId() != null && dep.toOperationId() != null
          && known.contains(dep.fromOperationId()) && known.contains(dep.toOperationId())
          && !dep.fromOperationId().equals(dep.toOperationId());
      if (!valid || !seen.add(dep.fromOperationId() + "->" + dep.toOperationId())) {
        dropped++;
        continue;
      }
      result.add(new DependencyInfo(dep.fromOperationId(), dep.toOperationId(),
          truncate(orDefault(dep.reason(), ""), MAX_LINE)));
    }
    if (dropped > 0) {
      warnings.add("유효하지 않거나 중복된 의존관계 " + dropped + "건을 제외했습니다.");
    }
    return result;
  }

  /**
   * 의존관계를 만족하는 실행 순서를 위상 정렬(Kahn)로 계산한다.
   * 의존이 없는 항목끼리는 기본 순서(생성 → 조회 → 수정 → 기타 → 삭제)를 따른다. 순환이 있으면 남은 항목을 기본 순서로 붙인다.
   */
  private List<String> computeTestOrder(List<EndpointInfo> endpoints, List<DependencyInfo> deps,
      List<String> warnings) {
    List<EndpointInfo> baseOrder = endpoints.stream()
        .sorted(Comparator.comparingInt(AnalysisNormalizer::phase)).toList();
    Map<String, Integer> indegree = new LinkedHashMap<>();
    Map<String, List<String>> next = new LinkedHashMap<>();
    baseOrder.forEach(ep -> {
      indegree.put(ep.operationId(), 0);
      next.put(ep.operationId(), new ArrayList<>());
    });
    for (DependencyInfo dep : deps) {
      next.get(dep.fromOperationId()).add(dep.toOperationId());
      indegree.merge(dep.toOperationId(), 1, Integer::sum);
    }

    List<String> order = new ArrayList<>();
    Set<String> placed = new LinkedHashSet<>();
    while (order.size() < baseOrder.size()) {
      // 기본 순서상 가장 앞에 있는, 선행 조건이 모두 끝난 항목을 하나씩 선택
      String pick = null;
      for (EndpointInfo ep : baseOrder) {
        String id = ep.operationId();
        if (!placed.contains(id) && indegree.get(id) == 0) {
          pick = id;
          break;
        }
      }
      if (pick == null) {
        warnings.add("의존관계에 순환이 있어 일부 항목은 기본 순서로 배치했습니다.");
        for (EndpointInfo ep : baseOrder) {
          if (placed.add(ep.operationId())) {
            order.add(ep.operationId());
          }
        }
        break;
      }
      placed.add(pick);
      order.add(pick);
      for (String following : next.get(pick)) {
        indegree.merge(following, -1, Integer::sum);
      }
    }
    return order;
  }

  /** 생성(컬렉션 POST) 0 → 조회 1 → 수정 2 → 기타 POST 3 → 삭제 4 */
  private static int phase(EndpointInfo ep) {
    return switch (ep.method()) {
      case "POST" -> ep.path().contains("{") ? 3 : 0;
      case "GET" -> 1;
      case "PUT", "PATCH" -> 2;
      case "DELETE" -> 4;
      default -> 3;
    };
  }

  // ---------------- 인증·주의사항 ----------------

  private AuthAnalysis normalizeAuth(AuthAnalysis ai, List<EndpointInfo> endpoints,
      List<SecuritySchemeInfo> schemes) {
    boolean required = endpoints.stream().anyMatch(EndpointInfo::secured);
    String scheme = schemes.isEmpty() ? "" : schemes.get(0).name();
    String description = ai != null && notBlank(ai.description()) ? truncate(ai.description(), MAX_LINE)
        : (required ? "인증이 필요한 엔드포인트가 있습니다." : "인증이 필요하지 않습니다.");
    return new AuthAnalysis(required, scheme, description);
  }

  private List<String> normalizeCautions(List<String> cautions) {
    if (cautions == null) {
      return List.of();
    }
    return cautions.stream().filter(AnalysisNormalizer::notBlank)
        .map(c -> truncate(c.trim(), MAX_LINE)).distinct().limit(MAX_CAUTIONS).toList();
  }

  // ---------------- 유틸 ----------------

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String orDefault(String s, String fallback) {
    return notBlank(s) ? s : fallback;
  }

  private static String truncate(String s, int max) {
    return s.length() > max ? s.substring(0, max) + "…" : s;
  }
}
