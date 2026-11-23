package com.inzent.airestclient.mock;

import com.inzent.airestclient.mock.MockModels.Customer;
import com.inzent.airestclient.mock.MockModels.CustomerRequest;
import com.inzent.airestclient.mock.MockModels.ErrorResponse;
import com.inzent.airestclient.mock.MockModels.Order;
import com.inzent.airestclient.mock.MockModels.OrderRequest;
import com.inzent.airestclient.mock.MockModels.OrderStatus;
import com.inzent.airestclient.mock.MockModels.Product;
import com.inzent.airestclient.mock.MockModels.ProductCategory;
import com.inzent.airestclient.mock.MockModels.ProductPage;
import com.inzent.airestclient.mock.MockModels.ProductRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 샘플 명세(samples/shop-sample.yaml)를 구현한 내장 Mock 서버.
 * 통신테스트 대상 서버가 없는 환경에서 실제 HTTP 호출을 검증하기 위해 제공한다.
 * 에러 응답은 앱 공통 응답(ApiResponse)이 아니라 샘플 명세의 ErrorResponse 형식을 따른다.
 */
@RestController
@RequestMapping("/mock")
public class MockShopController {

  private final MockShopService service;

  public MockShopController(MockShopService service) {
    this.service = service;
  }

  // ---------------- 상품 ----------------

  @GetMapping("/products")
  public ProductPage listProducts(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) ProductCategory category) {
    return service.listProducts(page, size, category);
  }

  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  public Product createProduct(@Valid @RequestBody ProductRequest request) {
    return service.createProduct(request);
  }

  @GetMapping("/products/{productId}")
  public Product getProduct(@PathVariable long productId) {
    return service.getProduct(productId);
  }

  @PutMapping("/products/{productId}")
  public Product updateProduct(
      @PathVariable long productId, @Valid @RequestBody ProductRequest request) {
    return service.updateProduct(productId, request);
  }

  @DeleteMapping("/products/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteProduct(@PathVariable long productId) {
    service.deleteProduct(productId);
  }

  // ---------------- 고객 ----------------

  @GetMapping("/customers")
  public List<Customer> listCustomers() {
    return service.listCustomers();
  }

  @PostMapping("/customers")
  @ResponseStatus(HttpStatus.CREATED)
  public Customer createCustomer(@Valid @RequestBody CustomerRequest request) {
    return service.createCustomer(request);
  }

  @GetMapping("/customers/{customerId}")
  public Customer getCustomer(@PathVariable long customerId) {
    return service.getCustomer(customerId);
  }

  // ---------------- 주문 ----------------

  @GetMapping("/orders")
  public List<Order> listOrders(@RequestParam(required = false) OrderStatus status) {
    return service.listOrders(status);
  }

  @PostMapping("/orders")
  @ResponseStatus(HttpStatus.CREATED)
  public Order createOrder(@Valid @RequestBody OrderRequest request) {
    return service.createOrder(request);
  }

  @GetMapping("/orders/{orderId}")
  public Order getOrder(@PathVariable long orderId) {
    return service.getOrder(orderId);
  }

  @PostMapping("/orders/{orderId}/cancel")
  public Order cancelOrder(@PathVariable long orderId) {
    return service.cancelOrder(orderId);
  }

  // ---------------- 에러 처리 (컨트롤러 로컬: 전역 핸들러보다 우선) ----------------

  @ExceptionHandler(MockApiException.class)
  public ResponseEntity<ErrorResponse> handleMock(MockApiException e) {
    return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getCode(), e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
    String message = "요청 형식 또는 값이 올바르지 않습니다.";
    if (e instanceof MethodArgumentNotValidException validation) {
      message = validation.getBindingResult().getFieldErrors().stream()
          .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
          .reduce((a, b) -> a + ", " + b)
          .orElse(message);
    }
    return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
  }
}
