package com.inzent.airestclient.service;

import com.inzent.airestclient.common.BusinessException;
import com.inzent.airestclient.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사용자가 입력한 URL에서 Swagger 명세를 내려받는다.
 * SSRF 방어: http/https만 허용, 사설·루프백·링크로컬 주소 차단, 리다이렉트 미추적, 크기·시간 제한.
 * (DNS 재바인딩까지 완전히 막지는 못하므로 운영 환경에서는 네트워크 레벨 egress 제한을 함께 권장)
 */
@Component
public class UrlSpecFetcher {

  private static final int MAX_BYTES = 10 * 1024 * 1024;

  private final boolean allowPrivateUrls;
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public UrlSpecFetcher(@Value("${app.spec.allow-private-urls:false}") boolean allowPrivateUrls) {
    this.allowPrivateUrls = allowPrivateUrls;
  }

  public String fetch(String url) {
    URI uri = parseAndValidate(url);
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json, application/yaml, text/yaml, */*")
        .GET()
        .build();
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST,
            "URL 응답 상태가 200이 아닙니다: " + response.statusCode());
      }
      try (InputStream in = response.body()) {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
          throw new BusinessException(ErrorCode.INVALID_REQUEST, "명세 크기가 10MB를 초과합니다.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "URL에서 명세를 가져오지 못했습니다.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "URL 요청이 중단되었습니다.", e);
    }
  }

  private URI parseAndValidate(String url) {
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "URL 형식이 올바르지 않습니다.", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "http/https URL만 사용할 수 있습니다.");
    }
    if (!allowPrivateUrls) {
      assertPublicHost(uri.getHost());
    }
    return uri;
  }

  private void assertPublicHost(String host) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "호스트를 찾을 수 없습니다: " + host, e);
    }
    for (InetAddress address : addresses) {
      if (isInternal(address)) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST,
            "내부/사설 네트워크 주소는 허용되지 않습니다.");
      }
    }
  }

  private boolean isInternal(InetAddress a) {
    byte[] raw = a.getAddress();
    // IPv6 고유 로컬 주소(fc00::/7)
    boolean uniqueLocalV6 = raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
    return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
        || a.isSiteLocalAddress() || a.isMulticastAddress() || uniqueLocalV6;
  }
}
