package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.TestCaseResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResult, Long> {

  List<TestCaseResult> findByRunIdOrderByIdAsc(Long runId);
}
