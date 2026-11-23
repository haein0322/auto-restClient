/** SSE 한 건(이벤트명 + 데이터 문자열) */
export interface SseMessage {
  event: string;
  data: string;
}

/**
 * fetch 응답 본문(text/event-stream)을 이벤트 단위로 읽는다.
 * EventSource는 GET만 지원하므로, POST로 시작하는 스트림은 이 함수로 파싱한다.
 */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseMessage> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");

      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const message = parseBlock(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        if (message) {
          yield message;
        }
        boundary = buffer.indexOf("\n\n");
      }
    }
    const rest = parseBlock(buffer.trim());
    if (rest) {
      yield rest;
    }
  } finally {
    reader.releaseLock();
  }
}

function parseBlock(block: string): SseMessage | null {
  let event = "message";
  const dataLines: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^ /, ""));
    }
  }
  return dataLines.length > 0 ? { event, data: dataLines.join("\n") } : null;
}
