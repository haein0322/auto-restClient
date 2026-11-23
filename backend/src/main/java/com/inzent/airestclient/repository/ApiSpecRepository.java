package com.inzent.airestclient.repository;

import com.inzent.airestclient.domain.ApiSpec;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiSpecRepository extends JpaRepository<ApiSpec, Long> {

  List<ApiSpec> findAllByOrderByIdDesc();
}
