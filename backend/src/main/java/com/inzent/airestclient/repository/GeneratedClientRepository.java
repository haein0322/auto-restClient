package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.GeneratedClient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedClientRepository extends JpaRepository<GeneratedClient, Long> {

  Optional<GeneratedClient> findFirstBySpecIdOrderByIdDesc(Long specId);
}
