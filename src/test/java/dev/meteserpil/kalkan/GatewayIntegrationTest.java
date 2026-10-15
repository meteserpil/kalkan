package dev.meteserpil.kalkan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** Sahte bir LLM sağlayıcısı son kullanıcı mesajını geri yankılar; LLM'e ham verinin hiç ulaşmadığını doğrularız. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "Benim TC'm 10000000146, kartım 4111 1111 1111 1111.";
    static HttpServer fakeLlm;
    static volatile String receivedByLlm;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) throws IOException {
        fakeLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLlm.createContext("/v1/chat/completions", exchange -> {
            receivedByLlm = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            JsonNode req = JSON.readTree(receivedByLlm);
            JsonNode messages = req.get("messages");
            String echo = messages.get(messages.size() - 1).get("content").asText();
            StringBuilder body = new StringBuilder();
            if (req.path("stream").asBoolean()) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                for (int i = 0; i < echo.length(); i += 3) { // placeholder'ları bilerek chunk'lara böl
                    String part = echo.substring(i, Math.min(i + 3, echo.length()));
                    body.append("data: ").append(JSON.writeValueAsString(Map.of("choices",
                            List.of(Map.of("index", 0, "delta", Map.of("content", part)))))).append("\n\n");
                }
                body.append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
                body.append("data: [DONE]\n\n");
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body.append(JSON.writeValueAsString(Map.of("choices",
                        List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", echo))))));
            }
            byte[] bytes = body.toString().getBytes(UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        fakeLlm.start();
        registry.add("kalkan.upstream-url", () -> "http://127.0.0.1:" + fakeLlm.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stop() {
        fakeLlm.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Test
    void nonStreamingMasksRequestAndRestoresResponse() throws IOException {
        byte[] resp = client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "x", "messages", List.of(Map.of("role", "user", "content", SECRET))))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Kalkan-Masked", "KART=1,TCKN=1")
                .expectBody().returnResult().getResponseBody();

        assertThat(receivedByLlm).doesNotContain("10000000146", "4111").contains("[TCKN_1]", "[KART_1]");
        assertThat(JSON.readTree(resp).at("/choices/0/message/content").asText()).isEqualTo(SECRET);
    }

    @Test
    void streamingRestoresPlaceholdersSplitAcrossChunks() throws IOException {
        List<String> events = client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "x", "stream", true, "messages", List.of(Map.of("role", "user", "content", SECRET))))
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody().collectList().block();

        StringBuilder text = new StringBuilder();
        for (String e : events) {
            if (!e.equals("[DONE]")) text.append(JSON.readTree(e).at("/choices/0/delta/content").asText());
        }
        assertThat(receivedByLlm).doesNotContain("10000000146");
        assertThat(text.toString()).isEqualTo(SECRET);
        assertThat(events).last().isEqualTo("[DONE]");
    }

    @Test
    void multimodalTextPartsAreMaskedToo() {
        client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "x", "messages", List.of(
                        Map.of("role", "system", "content", List.of(Map.of("type", "text", "text", "IBAN TR330006100519786457841326"))),
                        Map.of("role", "user", "content", "merhaba"))))
                .exchange().expectStatus().isOk();

        assertThat(receivedByLlm).doesNotContain("TR330006100519786457841326").contains("[IBAN_1]");
    }
}
