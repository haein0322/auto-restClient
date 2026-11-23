package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.TestRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

  List<TestRun> findBySpecIdOrderByIdDesc(Long specId);
}
