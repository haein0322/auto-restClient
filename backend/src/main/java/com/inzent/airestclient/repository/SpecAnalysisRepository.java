package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.SpecAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecAnalysisRepository extends JpaRepository<SpecAnalysis, Long> {

  Optional<SpecAnalysis> findFirstBySpecIdOrderByIdDesc(Long specId);
}
