package dev.meteserpil.kalkan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kural tabanlı Türkçe kişisel veri tespiti. Regex adayları checksum ile doğrulanır, böylece rastgele sayılar yakalanmaz. */
public final class PiiDetector {

    public enum Type { EPOSTA, IBAN, KART, TCKN, TELEFON }

    public record Match(Type type, int start, int end, String value) {}

    private record Rule(Type type, Pattern pattern, Predicate<String> valid) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Type.EPOSTA, Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"), s -> true),
            new Rule(Type.IBAN, Pattern.compile("(?i)\\bTR\\d{2}(?: ?\\d{4}){5} ?\\d{2}\\b"), PiiDetector::validIban),
            new Rule(Type.KART, Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"), PiiDetector::validLuhn),
            new Rule(Type.TCKN, Pattern.compile("\\b[1-9]\\d{10}\\b"), PiiDetector::validTckn),
            new Rule(Type.TELEFON, Pattern.compile("(?<![\\d+])(?:\\+90 ?|0)?5\\d{2} ?\\d{3} ?\\d{2} ?\\d{2}(?!\\d)"), s -> true));

    private PiiDetector() {}

    /** Çakışmayan eşleşmeleri metindeki sırasıyla döner; çakışmada önce başlayan, sonra uzun olan kazanır. */
    public static List<Match> detect(String text) {
        List<Match> all = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                if (rule.valid().test(m.group())) all.add(new Match(rule.type(), m.start(), m.end(), m.group()));
            }
        }
        all.sort(Comparator.comparingInt(Match::start).thenComparingInt(m -> m.start() - m.end()));
        List<Match> result = new ArrayList<>();
        int last = 0;
        for (Match m : all) {
            if (m.start() >= last) {
                result.add(m);
                last = m.end();
            }
        }
        return result;
    }

    static boolean validTckn(String s) {
        int[] d = s.chars().map(c -> c - '0').toArray();
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        int sum10 = 0;
        for (int i = 0; i < 10; i++) sum10 += d[i];
        return d[9] == Math.floorMod(odd * 7 - even, 10) && d[10] == sum10 % 10;
    }

    static boolean validIban(String s) {
        String iban = s.replace(" ", "").toUpperCase();
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) digits.append(Character.getNumericValue(c)); // A=10 ... Z=35
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    static boolean validLuhn(String s) {
        String digits = s.replaceAll("[ -]", "");
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            sum += d;
        }
        return sum % 10 == 0;
    }
}
