package br.com.quickfiller.extraction;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TokenSanitizer {
    private static final Pattern DATE = Pattern.compile(
            "^([\\d?]{1,2})([/.-])([\\d?]{1,2})\\2([\\d?]{2,4})$");
    private static final Pattern MONEY = Pattern.compile(
            "[+-]?(?:[\\d?]{1,3}(?:\\.[\\d?]{3})+|[\\d?]+),[\\d?]{2}");

    private TokenSanitizer() {}

    public static String safeDateRaw(String raw) {
        String value = raw == null ? "" : raw.strip();
        Matcher matcher = DATE.matcher(value);
        if (!matcher.matches() || value.contains("?")) return value;
        int day = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(3));
        String yearText = matcher.group(4);
        String separator = matcher.group(2);
        if (month < 1 || month > 12) {
            return matcher.group(1) + separator + "?".repeat(matcher.group(3).length())
                    + separator + yearText;
        }
        if (day < 1 || day > 31) {
            return "?".repeat(matcher.group(1).length()) + separator + matcher.group(3)
                    + separator + yearText;
        }
        int maximum;
        if (yearText.length() == 4) {
            maximum = YearMonth.of(Integer.parseInt(yearText), month).lengthOfMonth();
        } else {
            maximum = month == 2 ? 29 : YearMonth.of(2000, month).lengthOfMonth();
        }
        if (day > maximum) {
            return "?".repeat(matcher.group(1).length()) + separator + matcher.group(3)
                    + separator + yearText;
        }
        return value;
    }

    public static SanitizedTime safeTime(String raw) {
        String value = raw == null ? "" : raw.strip();
        String compact = value.replaceAll("\\s+", "").replace('h', ':').replace('H', ':').replace('.', ':');
        String[] parts = compact.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[\\d?]{1,2}") || !parts[1].matches("[\\d?]{2}")) {
            return new SanitizedTime(value, value);
        }
        String hourText = parts[0];
        String minuteText = parts[1];
        if (hourText.contains("?") || minuteText.contains("?")) {
            String normalizedHour = hourText.length() == 1 ? "0" + hourText : hourText;
            return new SanitizedTime(value, normalizedHour + ":" + minuteText);
        }
        int hour = Integer.parseInt(hourText);
        int minute = Integer.parseInt(minuteText);
        String safeRaw = value;
        if (hour > 23) {
            safeRaw = replaceTimePart(value, true);
            hourText = "?".repeat(hourText.length());
        }
        if (minute > 59) {
            safeRaw = replaceTimePart(safeRaw, false);
            minuteText = "??";
        }
        if (hour <= 23 && minute <= 59) {
            return new SanitizedTime(value, String.format("%02d:%02d", hour, minute));
        }
        String normalizedHour = hourText.length() == 1 ? "?" + hourText : hourText;
        return new SanitizedTime(safeRaw, normalizedHour + ":" + minuteText);
    }

    private static String replaceTimePart(String raw, boolean hour) {
        int separator = Math.max(Math.max(raw.indexOf(':'), raw.indexOf('h')),
                Math.max(raw.indexOf('H'), raw.indexOf('.')));
        if (separator < 0) return raw.replaceAll("\\d", "?");
        if (hour) return raw.substring(0, separator).replaceAll("\\d", "?") + raw.substring(separator);
        return raw.substring(0, separator + 1) + raw.substring(separator + 1).replaceAll("\\d", "?");
    }

    public static String safeBrazilianNumber(String raw) {
        if (raw == null) return "";
        String value = raw.strip();
        return MONEY.matcher(value).matches() ? value : value.replaceAll("[^\\d.,?]", "?");
    }

    public static LocalDate readableDate(String raw) {
        if (raw == null || raw.contains("?")) return null;
        Matcher matcher = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$").matcher(raw.strip());
        if (!matcher.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    public record SanitizedTime(String raw, String normalized) {}
}
