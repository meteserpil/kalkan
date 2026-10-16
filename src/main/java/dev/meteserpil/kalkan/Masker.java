package dev.meteserpil.kalkan;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bir istek boyunca yaşar. Aynı değer her yerde aynı placeholder'ı alır ("[TCKN_1]"),
 * böylece LLM konuşma geçmişinde tutarlı akıl yürütür. Eşleme sadece bellekte durur, hiçbir yere yazılmaz.
 */
public final class Masker {

    private final Map<String, String> placeholderByKey = new HashMap<>();
    private final Map<String, String> valueByPlaceholder = new HashMap<>();
    private final Map<PiiDetector.Type, Integer> counts = new EnumMap<>(PiiDetector.Type.class);

    public String mask(String text) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (PiiDetector.Match m : PiiDetector.detect(text)) {
            // "TR33 0006 ..." ile "TR330006..." aynı kişi verisidir
            String key = m.type() + ":" + m.value().replaceAll("[ -]", "").toUpperCase();
            String placeholder = placeholderByKey.computeIfAbsent(key, k -> {
                String p = "[" + m.type() + "_" + counts.merge(m.type(), 1, Integer::sum) + "]";
                valueByPlaceholder.put(p, m.value());
                return p;
            });
            out.append(text, pos, m.start()).append(placeholder);
            pos = m.end();
        }
        return out.append(text, pos, text.length()).toString();
    }

    /** Tür başına maskelenen farklı değer sayısı. */
    public Map<PiiDetector.Type, Integer> counts() {
        return Map.copyOf(counts);
    }

    /** "TCKN=1,KART=2" gibi; ham veri içermez, log ve header için güvenli. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        counts.forEach((type, n) -> s.append(s.isEmpty() ? "" : ",").append(type).append('=').append(n));
        return s.toString();
    }

    public String restore(String text) {
        Restorer r = restorer();
        return r.feed(text) + r.flush();
    }

    public Restorer restorer() {
        return new Restorer();
    }

    /**
     * Streaming geri çevirici. LLM "[TCK" ve "N_1]" parçalarını ayrı chunk'larda gönderebilir;
     * placeholder'a benzeyen kısmı tamamlanana ya da placeholder olmadığı anlaşılana kadar bekletir.
     */
    public final class Restorer {
        private static final Pattern PARTIAL = Pattern.compile("\\[[A-Z]*(?:_\\d*)?");
        private static final int MAX_PLACEHOLDER = 24;
        private final StringBuilder pending = new StringBuilder();

        public String feed(String chunk) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < chunk.length(); i++) accept(chunk.charAt(i), out);
            return out.toString();
        }

        public String flush() {
            String rest = pending.toString();
            pending.setLength(0);
            return rest;
        }

        private void accept(char c, StringBuilder out) {
            if (pending.isEmpty()) {
                if (c == '[') pending.append(c);
                else out.append(c);
                return;
            }
            pending.append(c);
            if (c == ']') {
                String p = flush();
                out.append(valueByPlaceholder.getOrDefault(p, p));
            } else if (pending.length() > MAX_PLACEHOLDER || !PARTIAL.matcher(pending).matches()) {
                // placeholder değilmiş: son karakter hariç olduğu gibi bırak, son karakteri yeniden değerlendir ("[[TCKN_1]" gibi)
                pending.setLength(pending.length() - 1);
                out.append(flush());
                accept(c, out);
            }
        }
    }
}
