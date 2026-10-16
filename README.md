# Kalkan: KVKK Uyumlu LLM Gateway
![CI](https://github.com/meteserpil/kalkan/actions/workflows/ci.yml/badge.svg)

Şirket uygulamaları ile LLM sağlayıcıları (OpenAI, OpenRouter, Ollama…) arasına giren bir proxy.
Prompt'taki Türkçe kişisel verileri **LLM'e gitmeden** maskeler, cevapta geri çevirir.
İstemci tarafında tek değişiklik `base_url`.

```
Uygulama ──► Kalkan ──(maskelenmiş: "TC'm [TCKN_1]")──► LLM
         ◄── Kalkan ◄──(cevap: "[TCKN_1] doğrulandı")── LLM
   "10000000146 doğrulandı"
```

## Neler tespit ediliyor

| Tür | Yöntem |
|---|---|
| TCKN | Regex + TC kimlik algoritması (10. ve 11. hane kontrolü) |
| IBAN (TR) | Regex + ISO 13616 mod-97 |
| Kart numarası | Regex + Luhn |
| Telefon (TR mobil) | Regex (`+90`, `0`, boşluklu biçimler) |
| E-posta | Regex |

Checksum doğrulaması sayesinde "Sipariş no 12345678901" gibi rastgele sayılar maskelenmez.

## Öne çıkan mühendislik noktaları

- **Streaming geri çevirme:** LLM `[TCK` ve `N_1]` parçalarını ayrı SSE chunk'larında gönderebilir. `Masker.Restorer`
  placeholder'a benzeyen kısmı tamamlanana kadar bekletir. Placeholder olmadığı anlaşılırsa (`[not]`) metni olduğu gibi geçirir.
- **Tutarlı placeholder:** Aynı değer bir istekte her geçtiği yerde aynı placeholder'ı alır (`TR33 0006…` ile `TR330006…` aynı kabul edilir),
  böylece LLM konuşma geçmişinde tutarlı akıl yürütür.
- **Hiçbir alan kaçmaz:** `messages` altındaki her string maskelenir: düz `content`, multimodal `text` parçaları, tool argümanları.
- **Durumsuz (stateless):** Eşleme sadece istek süresince bellekte durur, diske ya da loga ham veri yazılmaz. Yatayda ölçeklenir.
- **Şeffaf:** `Authorization` header'ı olduğu gibi iletilir. Yanıttaki `X-Kalkan-Masked: KART=1,TCKN=1` header'ı neyin maskelendiğini gösterir.

## Çalıştırma

Java 21 gerekir. Maven wrapper projeyle birlikte gelir.

```bash
./mvnw test                     # birim + entegrasyon testleri
./mvnw spring-boot:run          # http://localhost:8080
```

Upstream sağlayıcı `KALKAN_UPSTREAM_URL` ile değiştirilir (varsayılan `https://api.openai.com/v1`):

```bash
KALKAN_UPSTREAM_URL=http://localhost:11434/v1 ./mvnw spring-boot:run   # yerel Ollama
```

Deneme:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"TC kimliğim 10000000146, bunu tekrar et"}]}'
```

## Gözlemlenebilirlik

| Endpoint | İçerik |
|---|---|
| `/actuator/health` | Sağlık kontrolü (load balancer, Kubernetes probe) |
| `/actuator/prometheus` | `kalkan_pii_masked_total{type="TCKN"}` gibi tür bazında maskeleme sayaçları, gelen istekler (`http_server_requests`) ve LLM'e giden çağrıların gecikmesi (`http_client_requests`) |

Metriklerde sadece sayılar tutulur, ham kişisel veri hiçbir zaman metriklere ya da loglara yazılmaz.

## Yol haritası

- [x] Kural tabanlı tespit, maskeleme, streaming geri çevirme
- [ ] BERTurk NER servisi ile isim, adres ve kurum tespiti (Python, ONNX, gRPC)
- [ ] Tenant bazlı politika: maskele / engelle / izin ver
- [x] Prometheus metrikleri ve health endpoint
- [ ] Async audit log (ham veri yerine hash)
- [ ] Sentetik Türkçe PII eval seti, CI'da recall kapısı, k6 ile gecikme ölçümü
