package br.com.quickfiller.warnings;

import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.extraction.TokenSanitizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WarningService {

    public List<RowWarning> timecard(TimecardTranscription transcription) {
        List<RowWarning> warnings = new ArrayList<>();
        LocalDate previous = null;
        for (TimecardTranscription.Page page : transcription.pages()) {
            for (TimecardTranscription.Day day : page.days()) {
                List<String> reasons = new ArrayList<>();
                Level level = Level.NONE;
                LocalDate current = TokenSanitizer.readableDate(day.dateRaw());
                if (current != null) {
                    if (previous != null && !current.equals(previous.plusDays(1))) {
                        reasons.add("data não sequencial");
                        level = Level.RED;
                    }
                    previous = current;
                }
                if (day.punches().size() % 2 != 0) {
                    reasons.add("batidas ímpares");
                    if (level == Level.NONE) level = Level.YELLOW;
                }
                if (containsQuestion(day)) {
                    reasons.add("leitura contém ?");
                    if (level == Level.NONE) level = Level.YELLOW;
                }
                warnings.add(new RowWarning(level, List.copyOf(reasons)));
            }
        }
        return List.copyOf(warnings);
    }

    public List<RowWarning> payslip(PayslipTranscription transcription) {
        List<RowWarning> warnings = new ArrayList<>();
        YearMonth previous = null;
        for (PayslipTranscription.Page page : transcription.pages()) {
            List<String> reasons = new ArrayList<>();
            Level level = Level.NONE;
            YearMonth current = readableCompetence(page);
            if (current != null) {
                if (previous != null && !current.equals(previous.plusMonths(1))) {
                    reasons.add("mês não sequencial");
                    level = Level.RED;
                }
                previous = current;
            }
            if (page.fields().isEmpty() && page.bases().isEmpty()) {
                reasons.add("página vazia");
                if (level == Level.NONE) level = Level.YELLOW;
            }
            if (containsQuestion(page)) {
                reasons.add("leitura contém ?");
                if (level == Level.NONE) level = Level.YELLOW;
            }
            warnings.add(new RowWarning(level, List.copyOf(reasons)));
        }
        return List.copyOf(warnings);
    }

    private YearMonth readableCompetence(PayslipTranscription.Page page) {
        if (!page.year().matches("\\d{4}") || !page.month().matches("0[1-9]|1[0-2]")) return null;
        try { return YearMonth.of(Integer.parseInt(page.year()), Integer.parseInt(page.month())); }
        catch (DateTimeException exception) { return null; }
    }

    private boolean containsQuestion(TimecardTranscription.Day day) {
        if (day.dateRaw().contains("?")) return true;
        return day.punches().stream().anyMatch(punch -> punch.timeRaw().contains("?") || punch.timeHhmm().contains("?"));
    }

    private boolean containsQuestion(PayslipTranscription.Page page) {
        if (page.year().contains("?") || page.month().contains("?")) return true;
        if (page.fields().stream().anyMatch(field -> field.code().contains("?") || field.label().contains("?")
                || field.reference().contains("?") || field.value().contains("?"))) return true;
        return page.bases().stream().anyMatch(base -> base.label().contains("?") || base.value().contains("?"));
    }

    public enum Level { NONE, YELLOW, RED }
    public record RowWarning(Level level, List<String> reasons) {}
}
