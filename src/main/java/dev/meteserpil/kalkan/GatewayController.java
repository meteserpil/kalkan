package dev.meteserpil.kalkan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/** OpenAI uyumlu /v1/chat/completions. İstemci tarafında sadece base URL değişir. */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
    private static final String MASKED_HEADER = "X-Kalkan-Masked";

    private final WebClient upstream;
    private final ObjectMapper json;

    GatewayController(WebClient.Builder builder, ObjectMapper json, @Value("${kalkan.upstream-url}") String upstreamUrl) {
        this.upstream = builder.baseUrl(upstreamUrl).build();
        this.json = json;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> chat(@RequestBody ObjectNode body,
                                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        Masker masker = new Masker();
        maskAllText(body.path("messages"), masker);
        log.info("masked: {}", masker.summary());

        WebClient.RequestBodySpec request = upstream.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON);
        if (auth != null) request.header(HttpHeaders.AUTHORIZATION, auth);

        if (body.path("stream").asBoolean()) {
            Map<Integer, Masker.Restorer> restorers = new HashMap<>(); // n>1 seçenekte her choice ayrı akar
            Flux<ServerSentEvent<String>> events = request.bodyValue(body).accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve().bodyToFlux(String.class)
                    .map(data -> ServerSentEvent.builder(restoreChunk(data, masker, restorers)).build());
            return Mono.just(ResponseEntity.ok().header(MASKED_HEADER, masker.summary())
                    .contentType(MediaType.TEXT_EVENT_STREAM).body(events));
        }

        return request.bodyValue(body).retrieve().bodyToMono(ObjectNode.class).map(resp -> {
            for (JsonNode choice : resp.path("choices")) {
                JsonNode message = choice.path("message");
                if (message.path("content").isTextual()) {
                    ((ObjectNode) message).put("content", masker.restore(message.get("content").asText()));
                }
            }
            return ResponseEntity.ok().header(MASKED_HEADER, masker.summary()).body(resp);
        });
    }

    /** content string'i, multimodal "text" parçaları, tool argümanları: messages altındaki her string maskelenir, hiçbiri kaçmaz. */
    private static void maskAllText(JsonNode node, Masker masker) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fieldNames().forEachRemaining(name -> {
                JsonNode child = obj.get(name);
                if (child.isTextual()) obj.put(name, masker.mask(child.asText()));
                else maskAllText(child, masker);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (child.isTextual()) ((ArrayNode) node).set(i, masker.mask(child.asText()));
                else maskAllText(child, masker);
            }
        }
    }

    private String restoreChunk(String data, Masker masker, Map<Integer, Masker.Restorer> restorers) {
        JsonNode event;
        try {
            event = json.readTree(data);
        } catch (JsonProcessingException e) {
            return data; // "[DONE]"
        }
        for (JsonNode choice : event.path("choices")) {
            Masker.Restorer r = restorers.computeIfAbsent(choice.path("index").asInt(), i -> masker.restorer());
            JsonNode delta = choice.path("delta");
            boolean finished = !choice.path("finish_reason").isMissingNode() && !choice.path("finish_reason").isNull();
            if (delta.isObject() && (delta.path("content").isTextual() || finished)) {
                // shortcut: bekleyen metin finish_reason ile boşaltılır; finish_reason göndermeyen sağlayıcıda son ~24 karakter kaybolabilir
                String text = r.feed(delta.path("content").asText(""));
                ((ObjectNode) delta).put("content", finished ? text + r.flush() : text);
            }
        }
        return event.toString();
    }

    @ExceptionHandler(WebClientResponseException.class)
    ResponseEntity<String> upstreamError(WebClientResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
    }
}
