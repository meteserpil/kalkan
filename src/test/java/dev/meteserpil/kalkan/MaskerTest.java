package dev.meteserpil.kalkan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskerTest {

    static final String TEXT = "TCKN 10000000146, IBAN TR33 0006 1005 1978 6457 8413 26, kart 4111 1111 1111 1111, "
            + "tel 0532 123 45 67, mail ahmet.yilmaz@ornek.com.tr";

    @Test
    void masksEveryTypeAndRestoresOriginal() {
        Masker masker = new Masker();
        String masked = masker.mask(TEXT);

        assertThat(masked).isEqualTo("TCKN [TCKN_1], IBAN [IBAN_1], kart [KART_1], tel [TELEFON_1], mail [EPOSTA_1]");
        assertThat(masker.restore(masked)).isEqualTo(TEXT);
        assertThat(masker.summary()).isEqualTo("EPOSTA=1,IBAN=1,KART=1,TCKN=1,TELEFON=1");
    }

    @Test
    void rejectsNumbersFailingChecksum() {
        String text = "Sipariş no 12345678901, kart 4111 1111 1111 1112, IBAN TR33 0006 1005 1978 6457 8413 27";
        assertThat(new Masker().mask(text)).isEqualTo(text);
    }

    @Test
    void sameValueGetsSamePlaceholderAcrossFormats() {
        Masker masker = new Masker();
        assertThat(masker.mask("TR330006100519786457841326")).isEqualTo("[IBAN_1]");
        assertThat(masker.mask("TR33 0006 1005 1978 6457 8413 26")).isEqualTo("[IBAN_1]");
        assertThat(masker.mask("10000000146'nın sahibi")).isEqualTo("[TCKN_1]'nın sahibi");
    }

    @Test
    void streamingRestoresPlaceholderSplitAcrossChunks() {
        Masker masker = new Masker();
        String masked = masker.mask("Müşteri 10000000146 ve [not] [[ ok");
        Masker.Restorer r = masker.restorer();

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < masked.length(); i += 3) out.append(r.feed(masked.substring(i, Math.min(i + 3, masked.length()))));
        out.append(r.flush());

        assertThat(out.toString()).isEqualTo("Müşteri 10000000146 ve [not] [[ ok");
    }

    @Test
    void unknownPlaceholderIsLeftAsIs() {
        assertThat(new Masker().restore("[TCKN_9] ve [KART_1]")).isEqualTo("[TCKN_9] ve [KART_1]");
    }
}
