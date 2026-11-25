package com.inzent.airestclient.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 내장 Mock 서버(쇼핑몰 샘플 API)가 사용하는 요청/응답 DTO 모음 */
public final class MockModels {

  private MockModels() {}

  public enum ProductCategory { ELECTRONICS, BOOKS, FOOD, FASHION }

  public enum CustomerGrade { BASIC, VIP }

  public enum OrderStatus { CREATED, CANCELLED }

  public record ProductRequest(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 500) String description,
      @NotNull @DecimalMin("0") BigDecimal price,
      @NotNull ProductCategory category,
      @Min(0) Integer stock) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Product(
      Long id,
      String name,
      String description,
      BigDecimal price,
      ProductCategory category,
      int stock,
      Instant createdAt) {}

  public record ProductPage(List<Product> items, int page, int size, long totalElements) {}

  public record CustomerRequest(
      @NotBlank @Size(max = 50) String name,
      @NotBlank @Email String email,
      CustomerGrade grade) {}

  public record Customer(
      Long id, String name, String email, CustomerGrade grade, Instant createdAt) {}

  public record OrderItemRequest(
      @NotNull Long productId, @NotNull @Min(1) @Max(100) Integer quantity) {}

  public record OrderRequest(
      @NotNull Long customerId, @NotEmpty List<@Valid OrderItemRequest> items) {}

  public record OrderItem(Long productId, int quantity, BigDecimal unitPrice) {}

  public record Order(
      Long id,
      Long customerId,
      List<OrderItem> items,
      BigDecimal totalAmount,
      OrderStatus status,
      Instant createdAt) {}

  public record ItemRequest(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 500) String description,
      @NotNull @DecimalMin("0") BigDecimal price) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Item(Long id, String name, String description, BigDecimal price, Instant createdAt) {}

  public record ErrorResponse(String code, String message) {}
}
