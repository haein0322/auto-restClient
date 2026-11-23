package com.inzent.airestclient.mock;

import com.inzent.airestclient.mock.MockModels.Customer;
import com.inzent.airestclient.mock.MockModels.CustomerGrade;
import com.inzent.airestclient.mock.MockModels.CustomerRequest;
import com.inzent.airestclient.mock.MockModels.Order;
import com.inzent.airestclient.mock.MockModels.OrderItem;
import com.inzent.airestclient.mock.MockModels.OrderItemRequest;
import com.inzent.airestclient.mock.MockModels.OrderRequest;
import com.inzent.airestclient.mock.MockModels.OrderStatus;
import com.inzent.airestclient.mock.MockModels.Product;
import com.inzent.airestclient.mock.MockModels.ProductCategory;
import com.inzent.airestclient.mock.MockModels.ProductPage;
import com.inzent.airestclient.mock.MockModels.ProductRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 쇼핑몰 샘플 API의 인메모리 비즈니스 로직(상태 유지: 등록 후 조회 가능) */
@Service
public class MockShopService {

  private final Map<Long, Product> products = new ConcurrentHashMap<>();
  private final Map<Long, Customer> customers = new ConcurrentHashMap<>();
  private final Map<Long, Order> orders = new ConcurrentHashMap<>();
  private final AtomicLong productSeq = new AtomicLong();
  private final AtomicLong customerSeq = new AtomicLong();
  private final AtomicLong orderSeq = new AtomicLong();

  public MockShopService() {
    seed();
  }

  /** 초기 데이터 (상품 3건, 고객 1건) */
  private void seed() {
    createProduct(new ProductRequest("무선 이어폰", "노이즈 캔슬링 지원", new BigDecimal("89000"),
        ProductCategory.ELECTRONICS, 50));
    createProduct(new ProductRequest("Spring 입문서", "백엔드 개발자를 위한 입문서", new BigDecimal("32000"),
        ProductCategory.BOOKS, 120));
    createProduct(new ProductRequest("유기농 사과 1kg", null, new BigDecimal("9800"),
        ProductCategory.FOOD, 300));
    createCustomer(new CustomerRequest("홍길동", "hong@example.com", CustomerGrade.VIP));
  }

  // ---------------- 상품 ----------------

  public ProductPage listProducts(int page, int size, ProductCategory category) {
    if (page < 0 || size < 1 || size > 100) {
      throw new MockApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGING",
          "page는 0 이상, size는 1~100 이어야 합니다.");
    }
    List<Product> filtered = products.values().stream()
        .filter(p -> category == null || p.category() == category)
        .sorted((a, b) -> Long.compare(a.id(), b.id()))
        .toList();
    int from = Math.min(page * size, filtered.size());
    int to = Math.min(from + size, filtered.size());
    return new ProductPage(new ArrayList<>(filtered.subList(from, to)), page, size, filtered.size());
  }

  public Product getProduct(long id) {
    Product product = products.get(id);
    if (product == null) {
      throw new MockApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + id);
    }
    return product;
  }

  public Product createProduct(ProductRequest req) {
    long id = productSeq.incrementAndGet();
    Product product = new Product(id, req.name(), req.description(), req.price(), req.category(),
        req.stock() == null ? 0 : req.stock(), Instant.now());
    products.put(id, product);
    return product;
  }

  public Product updateProduct(long id, ProductRequest req) {
    Product old = getProduct(id);
    Product updated = new Product(id, req.name(), req.description(), req.price(), req.category(),
        req.stock() == null ? 0 : req.stock(), old.createdAt());
    products.put(id, updated);
    return updated;
  }

  public void deleteProduct(long id) {
    if (products.remove(id) == null) {
      throw new MockApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + id);
    }
  }

  // ---------------- 고객 ----------------

  public List<Customer> listCustomers() {
    return customers.values().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
  }

  public Customer getCustomer(long id) {
    Customer customer = customers.get(id);
    if (customer == null) {
      throw new MockApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "고객을 찾을 수 없습니다: " + id);
    }
    return customer;
  }

  public synchronized Customer createCustomer(CustomerRequest req) {
    boolean duplicated = customers.values().stream()
        .anyMatch(c -> c.email().equalsIgnoreCase(req.email()));
    if (duplicated) {
      throw new MockApiException(HttpStatus.CONFLICT, "DUPLICATED_EMAIL", "이미 등록된 이메일입니다: " + req.email());
    }
    long id = customerSeq.incrementAndGet();
    Customer customer = new Customer(id, req.name(), req.email(),
        req.grade() == null ? CustomerGrade.BASIC : req.grade(), Instant.now());
    customers.put(id, customer);
    return customer;
  }

  // ---------------- 주문 ----------------

  public List<Order> listOrders(OrderStatus status) {
    return orders.values().stream()
        .filter(o -> status == null || o.status() == status)
        .sorted((a, b) -> Long.compare(a.id(), b.id()))
        .toList();
  }

  public Order getOrder(long id) {
    Order order = orders.get(id);
    if (order == null) {
      throw new MockApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다: " + id);
    }
    return order;
  }

  /** 재고 검증과 차감이 원자적으로 이루어져야 하므로 동기화한다. */
  public synchronized Order createOrder(OrderRequest req) {
    getCustomer(req.customerId());

    // 1) 전체 검증(존재 여부, 재고)을 먼저 수행한 뒤 2) 재고를 차감한다.
    Map<Long, Integer> requested = new ConcurrentHashMap<>();
    for (OrderItemRequest item : req.items()) {
      requested.merge(item.productId(), item.quantity(), Integer::sum);
    }
    for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
      Product product = getProduct(entry.getKey());
      if (product.stock() < entry.getValue()) {
        throw new MockApiException(HttpStatus.CONFLICT, "OUT_OF_STOCK",
            "재고가 부족합니다: 상품 " + product.id() + " (재고 " + product.stock() + ")");
      }
    }

    List<OrderItem> items = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (OrderItemRequest item : req.items()) {
      Product product = getProduct(item.productId());
      items.add(new OrderItem(product.id(), item.quantity(), product.price()));
      total = total.add(product.price().multiply(BigDecimal.valueOf(item.quantity())));
    }
    requested.forEach((productId, quantity) -> adjustStock(productId, -quantity));

    long id = orderSeq.incrementAndGet();
    Order order = new Order(id, req.customerId(), items, total, OrderStatus.CREATED, Instant.now());
    orders.put(id, order);
    return order;
  }

  public synchronized Order cancelOrder(long id) {
    Order order = getOrder(id);
    if (order.status() == OrderStatus.CANCELLED) {
      throw new MockApiException(HttpStatus.CONFLICT, "ALREADY_CANCELLED", "이미 취소된 주문입니다: " + id);
    }
    // 취소 시 재고 복원 (삭제된 상품은 건너뜀)
    order.items().forEach(item -> adjustStock(item.productId(), item.quantity()));
    Order cancelled = new Order(order.id(), order.customerId(), order.items(), order.totalAmount(),
        OrderStatus.CANCELLED, order.createdAt());
    orders.put(id, cancelled);
    return cancelled;
  }

  private void adjustStock(long productId, int delta) {
    products.computeIfPresent(productId, (key, p) -> new Product(p.id(), p.name(), p.description(),
        p.price(), p.category(), p.stock() + delta, p.createdAt()));
  }
}
