package br.com.quickfiller.extraction.timecard;

import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription.Day;
import br.com.quickfiller.domain.timecard.TimecardTranscription.Page;
import br.com.quickfiller.domain.timecard.TimecardTranscription.Punch;
import br.com.quickfiller.domain.timecard.TimecardTranscription.PunchKind;
import br.com.quickfiller.extraction.PageText;
import br.com.quickfiller.extraction.TokenSanitizer;
import br.com.quickfiller.extraction.TranscriptionExtractor;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TimecardExtractor implements TranscriptionExtractor {
    private static final Pattern FULL_DATE_LINE = Pattern.compile(
            "^\\s*([\\d?]{1,2}[/.-][\\d?]{1,2}[/.-][\\d?]{2,4})(?=\\s|$)");
    private static final Pattern DAY_LINE = Pattern.compile(
            "(?iu)^\\s*([\\d?]{1,2})\\s*(?:-\\s*)?(?:SEG|TER|QUA|QUI|SEX|SAB|DOM|FER)\\b");
    private static final Pattern PAGE_COMPETENCE = Pattern.compile(
            "(?iu)(?:m[eéê]s\\s*/\\s*ano|compet[eê]ncia)\\s*:?\\s*"
                    + "([\\d?]{1,2})\\s*[/.-]\\s*([\\d?]{4})");
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\d?])([\\d?]{1,2}\\s*[:hH.]\\s*[\\d?]{2})(?![\\d?])");
    private static final Pattern OCCURRENCE = Pattern.compile(
            "(?iu)\\b(?:HE(?:-|\\s)|DESTACAMENTO|REG\\.?\\s|ABONO|AUS[ÊE]NCIA|FALTA)\\b");

    @Override
    public DocumentType supports() { return DocumentType.TIMECARD; }

    @Override
    public TimecardTranscription extract(List<PageText> pages) {
        List<Page> result = new ArrayList<>();
        for (PageText page : pages) {
            Competence competence = findPageCompetence(page.text());
            boolean scheduledJornadaColumn = hasScheduledJornadaColumn(page.text());
            List<MutableDay> extracted = new ArrayList<>();

            for (String line : page.text().split("\\R")) {
                Matcher fullDate = FULL_DATE_LINE.matcher(line);
                Matcher dayOnly = DAY_LINE.matcher(line);
                String dateRaw;
                int contentStart;
                boolean dayOnlyRow;
                if (fullDate.find()) {
                    dateRaw = TokenSanitizer.safeDateRaw(fullDate.group(1));
                    contentStart = fullDate.end();
                    dayOnlyRow = false;
                } else if (dayOnly.find()) {
                    dateRaw = dateFromHeader(dayOnly.group(1), competence);
                    contentStart = dayOnly.end();
                    dayOnlyRow = true;
                } else {
                    continue;
                }

                String content = line.substring(contentStart);
                List<TokenSanitizer.SanitizedTime> times = extractPunchTimes(
                        content, scheduledJornadaColumn && dayOnlyRow);
                appendOrMerge(extracted, dateRaw, times);
            }

            List<Day> days = new ArrayList<>();
            for (MutableDay extractedDay : extracted) {
                List<Punch> punches = new ArrayList<>();
                for (int index = 0; index < extractedDay.times.size(); index++) {
                    TokenSanitizer.SanitizedTime time = extractedDay.times.get(index);
                    punches.add(new Punch(index % 2 == 0 ? PunchKind.IN : PunchKind.OUT,
                            time.raw(), time.normalized()));
                }
                days.add(new Day(extractedDay.dateRaw, punches));
            }
            result.add(new Page(page.page(), days));
        }
        return new TimecardTranscription(result);
    }

    private List<TokenSanitizer.SanitizedTime> extractPunchTimes(String content, boolean skipJornada) {
        int firstTime = firstTimePosition(content);
        int pipe = firstTime < 0 ? -1 : content.indexOf('|', firstTime);
        int occurrence = occurrencePosition(content);
        int end = content.length();
        if (pipe >= 0) end = Math.min(end, pipe);
        if (occurrence >= 0) end = Math.min(end, occurrence);

        List<TokenSanitizer.SanitizedTime> result = new ArrayList<>();
        Matcher matcher = TIME.matcher(content.substring(0, end));
        while (matcher.find()) result.add(TokenSanitizer.safeTime(matcher.group(1)));
        if (skipJornada && !result.isEmpty()) result.remove(0);
        return result;
    }

    private int firstTimePosition(String content) {
        Matcher matcher = TIME.matcher(content);
        return matcher.find() ? matcher.start() : -1;
    }

    private int occurrencePosition(String content) {
        Matcher matcher = OCCURRENCE.matcher(content);
        return matcher.find() ? matcher.start() : -1;
    }

    private void appendOrMerge(
            List<MutableDay> days,
            String dateRaw,
            List<TokenSanitizer.SanitizedTime> times) {
        if (!days.isEmpty() && days.get(days.size() - 1).dateRaw.equals(dateRaw)) {
            days.get(days.size() - 1).times.addAll(times);
            return;
        }
        days.add(new MutableDay(dateRaw, new ArrayList<>(times)));
    }

    private Competence findPageCompetence(String text) {
        Matcher matcher = PAGE_COMPETENCE.matcher(text);
        if (!matcher.find()) return new Competence("", "");
        String month = matcher.group(1);
        String year = matcher.group(2);
        if (!month.contains("?")) {
            int numeric = Integer.parseInt(month);
            if (numeric < 1 || numeric > 12) return new Competence("??", year);
            month = String.format("%02d", numeric);
        } else if (month.length() == 1) {
            month = "0?";
        }
        return new Competence(month, year);
    }

    private String dateFromHeader(String dayRaw, Competence competence) {
        String day = dayRaw.length() == 1 ? "0" + dayRaw : dayRaw;
        if (competence.month.isBlank() || competence.year.isBlank()) return day + "/??/????";
        return TokenSanitizer.safeDateRaw(day + "/" + competence.month + "/" + competence.year);
    }

    private boolean hasScheduledJornadaColumn(String text) {
        String normalized = normalize(text);
        return normalized.contains("dia semana jornada entrada saida")
                || normalized.contains("jornada entrada saida ocorrencia");
    }

    private String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private record Competence(String month, String year) {}

    private static final class MutableDay {
        private final String dateRaw;
        private final List<TokenSanitizer.SanitizedTime> times;

        private MutableDay(String dateRaw, List<TokenSanitizer.SanitizedTime> times) {
            this.dateRaw = dateRaw;
            this.times = times;
        }
    }
}
