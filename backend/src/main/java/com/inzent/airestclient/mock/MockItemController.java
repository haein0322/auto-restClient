package com.inzent.airestclient.mock;

import com.inzent.airestclient.mock.MockModels.ErrorResponse;
import com.inzent.airestclient.mock.MockModels.Item;
import com.inzent.airestclient.mock.MockModels.ItemRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 샘플 명세(samples/sample-api.yaml)를 구현한 내장 Mock 서버.
 * 항목(Item) 3개 엔드포인트만 제공하며, 데이터는 메모리에만 보관한다.
 */
@RestController
@RequestMapping("/mock/items")
public class MockItemController {

  private final Map<Long, Item> items = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong(0);

  public MockItemController() {
    // 처음 조회해도 목록이 비어 있지 않도록 기본 항목 1건을 둔다.
    create(new ItemRequest("기본 항목", "서버 시작 시 미리 들어 있는 항목", BigDecimal.valueOf(1000)));
  }

  @GetMapping
  public List<Item> listItems(@RequestParam(defaultValue = "10") int limit) {
    if (limit < 1 || limit > 100) {
      throw new MockApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "limit은 1~100 사이여야 합니다.");
    }
    List<Item> all = new ArrayList<>(items.values());
    all.sort((a, b) -> Long.compare(b.id(), a.id()));
    return all.subList(0, Math.min(limit, all.size()));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Item createItem(@Valid @RequestBody ItemRequest request) {
    return create(request);
  }

  @GetMapping("/{itemId}")
  public Item getItem(@PathVariable long itemId) {
    Item item = items.get(itemId);
    if (item == null) {
      throw new MockApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "항목을 찾을 수 없습니다: " + itemId);
    }
    return item;
  }

  private Item create(ItemRequest request) {
    long id = sequence.incrementAndGet();
    Item item = new Item(id, request.name(), request.description(), request.price(), Instant.now());
    items.put(id, item);
    return item;
  }

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
