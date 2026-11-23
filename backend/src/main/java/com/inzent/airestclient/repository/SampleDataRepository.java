package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.SampleData;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SampleDataRepository extends JpaRepository<SampleData, Long> {

  List<SampleData> findBySpecId(Long specId);

  Optional<SampleData> findBySpecIdAndOperationId(Long specId, String operationId);

  /** 벌크 삭제(즉시 실행). 파생 삭제는 INSERT 뒤에 실행되어 (spec, operation) 유니크 제약과 충돌할 수 있다. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from SampleData s where s.specId = :specId")
  void deleteAllBySpecId(@Param("specId") Long specId);
}
