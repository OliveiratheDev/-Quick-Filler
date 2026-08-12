package br.com.quickfiller.extraction.payslip;

import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.payslip.PayslipTranscription.Base;
import br.com.quickfiller.domain.payslip.PayslipTranscription.Field;
import br.com.quickfiller.domain.payslip.PayslipTranscription.Page;
import br.com.quickfiller.extraction.PageText;
import br.com.quickfiller.extraction.TokenSanitizer;
import br.com.quickfiller.extraction.TranscriptionExtractor;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PayslipExtractor implements TranscriptionExtractor {
    private static final Pattern COMPETENCE = Pattern.compile(
            "(?<!\\d)([\\d?]{1,2})\\s*[/.-]\\s*([\\d?]{4})(?!\\d)");
    private static final Pattern MONEY = Pattern.compile(
            "(?<![\\d?])([+-]?(?:[\\d?]{1,3}(?:\\.[\\d?]{3})+|[\\d?]+),[\\d?]{2})(?![\\d?])");
    private static final Pattern CODE = Pattern.compile("^\\s*([\\p{Alnum}?/]{2,8})\\s+");
    private static final Pattern TEXT_REFERENCE = Pattern.compile(
            "(?iu)^(.*?)(?:\\s{2,}|\\s)([A-Z.]+/\\d{2,4}|S/\\s*F[ÉE]RIAS)$");
    private static final Pattern FINANCIAL_PERIOD = Pattern.compile(
            "(?iu)per[ií]odo\\s*:\\s*((?:19|20)\\d{2})\\s*/\\s*\\d{1,2}"
                    + "\\s*a\\s*((?:19|20)\\d{2})\\s*/\\s*\\d{1,2}");
    private static final Pattern FINANCIAL_COMPETENCE = Pattern.compile(
            "(?iu)m[eê]s\\s*:\\s*([\\p{L}]{3,9})\\s*[-/]\\s*(\\d{2}|(?:19|20)\\d{2})");
    private static final Pattern FINANCIAL_CODE = Pattern.compile(
            "(?<!\\S)(\\d{1,4})(?=\\s+\\p{L})");
    private static final Pattern FINANCIAL_REFERENCE = Pattern.compile(
            "(?u)^(.*\\D)\\s+([+-]?\\d+(?:,\\d{1,3})?)$");
    private static final List<BasePattern> BASE_PATTERNS = List.of(
            base("(?iu)Base\\s+(?:de\\s+)?I\\.?\\s*N\\.?\\s*S\\.?\\s*S\\.?|BASEDECALCULODOINSS", "Base INSS"),
            base("(?iu)Sal\\.?\\s*Contrib\\.?\\s*INSS", "Base INSS"),
            base("(?iu)Base\\s+(?:de\\s+|C[aá]lc\\.?\\s+)?I\\.?\\s*R\\.?\\s*R?\\.?\\s*F?\\.?|BASEDECALCULODOIRF", "Base IR"),
            base("(?iu)Base\\s+C[aá]lc\\.?\\s+F\\.?\\s*G\\.?\\s*T\\.?\\s*S\\.?|BASEDECALCULODOFGTS", "Base FGTS"),
            base("(?iu)F\\.?\\s*G\\.?\\s*T\\.?\\s*S\\.?\\s*(?:do\\s+M[eê]s|M[eê]s)?|VALORDOFGTS", "FGTS"),
            base("(?iu)[Tt]?[Oo]tal\\s+(?:de\\s+)?(?:Vencimentos|Proventos)|TOT\\.RENDIMENTOS", "Total Vencimentos"),
            base("(?iu)[Tt]?[Oo]tal\\s+(?:de\\s+)?Descontos|TOTALDESCONTOS", "Total Descontos"),
            base("(?iu)(?:Valor\\s+)?L[ií]quido\\b(?:\\s+a\\s+Receber)?|SALARIOLIQUIDONOMES", "Valor Líquido"),
            base("(?iu)VALORDOIRFARECOLHER", "Valor IR a Recolher"));
    private static final Map<String, String> MONTHS = monthNames();
    private static final Map<String, String> FINANCIAL_MONTHS = financialMonthNames();
    private static final Set<String> FINANCIAL_SUMMARIES = Set.of("remuneracaomes", "dias/horastrab");

    @Override
    public DocumentType supports() { return DocumentType.PAYSLIP; }

    @Override
    public PayslipTranscription extract(List<PageText> pages) {
        boolean financialStatement = pages.stream().map(PageText::text).map(PayslipExtractor::normalize)
                .anyMatch(text -> text.contains("fichafinanceira") || text.contains("ficha financeira"));
        if (financialStatement) {
            return extractFinancialStatement(pages);
        }

        List<Page> result = new ArrayList<>();
        for (PageText page : pages) {
            Competence competence = findCompetence(page.text());
            List<Field> fields = new ArrayList<>();
            List<Base> bases = new ArrayList<>();
            List<String> pendingBaseLabels = List.of();
            boolean inTable = false;
            boolean parallelColumns = false;
            boolean pendingParallelHeader = false;

            for (String line : page.text().split("\\R")) {
                String normalized = normalize(line);
                List<MatchValue> amounts = amounts(line);

                if (!pendingBaseLabels.isEmpty()) {
                    if (amounts.size() >= pendingBaseLabels.size()) {
                        for (int index = 0; index < pendingBaseLabels.size(); index++) {
                            bases.add(new Base(pendingBaseLabels.get(index),
                                    TokenSanitizer.safeBrazilianNumber(amounts.get(index).value())));
                        }
                    }
                    if (!normalized.isBlank()) pendingBaseLabels = List.of();
                }

                List<BaseOccurrence> occurrences = baseOccurrences(line);
                if (!occurrences.isEmpty()) {
                    int before = bases.size();
                    extractBases(line, occurrences, bases);
                    if (before == bases.size() && amounts.size() == occurrences.size()) {
                        for (int index = 0; index < occurrences.size(); index++) {
                            bases.add(new Base(occurrences.get(index).label(),
                                    TokenSanitizer.safeBrazilianNumber(amounts.get(index).value())));
                        }
                    }
                    if ((!inTable || isTableBoundary(normalized))
                            && before == bases.size() && amounts.isEmpty()) {
                        pendingBaseLabels = occurrences.stream().map(BaseOccurrence::label).toList();
                    }
                } else {
                    extractGenericTotal(line, normalized, amounts, bases);
                }

                if (mentionsParallelColumns(normalized) && !isTableHeader(normalized)) {
                    pendingParallelHeader = true;
                    continue;
                }
                if (isTableHeader(normalized)) {
                    inTable = true;
                    parallelColumns = pendingParallelHeader || count(normalized, "descricao") >= 2;
                    pendingParallelHeader = false;
                    continue;
                }
                if (!inTable) continue;
                if (isTableBoundary(normalized)) {
                    inTable = false;
                    parallelColumns = false;
                    continue;
                }

                if (parallelColumns) {
                    fields.addAll(extractParallelFields(line));
                } else {
                    Field field = extractField(line);
                    if (field != null) fields.add(field);
                }
            }
            result.add(new Page(page.page(), competence.year(), competence.month(),
                    deduplicateFields(fields), deduplicateBases(bases)));
        }
        return new PayslipTranscription(result);
    }

    private PayslipTranscription extractFinancialStatement(List<PageText> pages) {
        FinancialPeriod period = findFinancialPeriod(pages);
        List<Page> result = new ArrayList<>();
        FinancialSection current = null;
        for (PageText physicalPage : pages) {
            boolean continuedFromPreviousPage = current != null;
            boolean foundCompetence = false;
            for (String line : physicalPage.text().split("\\R")) {
                Matcher competenceMatcher = FINANCIAL_COMPETENCE.matcher(line);
                if (competenceMatcher.find()) {
                    foundCompetence = true;
                    if (current != null) {
                        String previousSectionRemainder = line.substring(competenceMatcher.end()).strip();
                        if (!previousSectionRemainder.isBlank()) {
                            extractFinancialLine(previousSectionRemainder, current);
                        }
                        addFinancialSection(result, current);
                    }
                    current = new FinancialSection(physicalPage.page(),
                            financialCompetence(competenceMatcher.group(1), competenceMatcher.group(2), period));
                    continue;
                }
                if (current != null) extractFinancialLine(line, current);
            }
            if (!continuedFromPreviousPage && !foundCompetence) {
                result.add(new Page(physicalPage.page(), "", "", List.of(), List.of()));
            }
        }
        if (current != null) addFinancialSection(result, current);
        return new PayslipTranscription(result);
    }

    private FinancialPeriod findFinancialPeriod(List<PageText> pages) {
        for (PageText page : pages) {
            Matcher matcher = FINANCIAL_PERIOD.matcher(page.text());
            if (matcher.find()) {
                return new FinancialPeriod(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            }
        }
        return new FinancialPeriod(-1, -1);
    }

    private Competence financialCompetence(String monthRaw, String yearRaw, FinancialPeriod period) {
        String month = FINANCIAL_MONTHS.getOrDefault(normalize(monthRaw), "??");
        if (yearRaw.length() == 4) return new Competence(yearRaw, month);

        int suffix = Integer.parseInt(yearRaw);
        List<Integer> candidates = new ArrayList<>();
        if (period.isKnown()) {
            for (int year = period.startYear(); year <= period.endYear(); year++) {
                if (year % 100 == suffix) candidates.add(year);
            }
        }
        String year = candidates.size() == 1 ? Integer.toString(candidates.get(0)) : "??" + yearRaw;
        return new Competence(year, month);
    }

    private void extractFinancialLine(String line, FinancialSection section) {
        List<BaseOccurrence> occurrences = baseOccurrences(line);
        if (!occurrences.isEmpty()) extractBases(line, occurrences, section.bases());

        String fieldText = withoutBaseSegments(line, occurrences).strip();
        if (fieldText.isBlank()) return;
        List<MatchValue> values = amounts(fieldText);
        if (values.isEmpty()) return;

        int split = -1;
        Matcher codeMatcher = FINANCIAL_CODE.matcher(fieldText);
        while (codeMatcher.find()) {
            if (codeMatcher.start() > values.get(0).end()) {
                split = codeMatcher.start();
                break;
            }
        }
        if (split < 0) {
            addFinancialField(section, fieldText);
            return;
        }
        addFinancialField(section, fieldText.substring(0, split));
        addFinancialField(section, fieldText.substring(split));
    }

    private String withoutBaseSegments(String line, List<BaseOccurrence> occurrences) {
        if (occurrences.isEmpty()) return line;
        char[] remaining = line.toCharArray();
        for (int index = 0; index < occurrences.size(); index++) {
            BaseOccurrence occurrence = occurrences.get(index);
            int limit = index + 1 < occurrences.size() ? occurrences.get(index + 1).start() : line.length();
            Matcher amount = MONEY.matcher(line);
            amount.region(occurrence.end(), limit);
            int end = amount.find() ? amount.end(1) : occurrence.end();
            for (int position = occurrence.start(); position < end; position++) remaining[position] = ' ';
        }
        return new String(remaining);
    }

    private void addFinancialField(FinancialSection section, String rawSegment) {
        String segment = rawSegment.strip();
        List<MatchValue> values = amounts(segment);
        if (values.isEmpty()) return;

        Matcher codeMatcher = Pattern.compile("^\\s*(\\d{1,4})\\s+(?=\\p{L})").matcher(segment);
        String code = codeMatcher.find() ? codeMatcher.group(1) : "";
        int labelStart = codeMatcher.find(0) ? codeMatcher.end() : 0;
        MatchValue firstValue = values.get(0);
        String label = cleanLabel(segment.substring(labelStart, firstValue.start()));
        String reference = "";
        if (values.size() >= 2) {
            reference = TokenSanitizer.safeBrazilianNumber(firstValue.value());
        } else {
            Matcher referenceMatcher = FINANCIAL_REFERENCE.matcher(label);
            if (referenceMatcher.matches()) {
                label = cleanLabel(referenceMatcher.group(1));
                reference = referenceMatcher.group(2);
            }
        }
        if (label.isBlank() || (code.isBlank() && FINANCIAL_SUMMARIES.contains(normalize(label)))) return;
        String value = TokenSanitizer.safeBrazilianNumber(values.get(values.size() - 1).value());
        section.fields().add(new Field(code, label, reference, value));
    }

    private void addFinancialSection(List<Page> result, FinancialSection section) {
        Page page = new Page(section.page(), section.competence().year(), section.competence().month(),
                deduplicateFields(section.fields()), deduplicateBases(section.bases()));
        if (!result.isEmpty()) {
            Page previous = result.get(result.size() - 1);
            if (previous.page() == page.page() && previous.year().equals(page.year())
                    && previous.month().equals(page.month())) {
                List<Field> fields = new ArrayList<>(previous.fields());
                fields.addAll(page.fields());
                List<Base> bases = new ArrayList<>(previous.bases());
                bases.addAll(page.bases());
                result.set(result.size() - 1, new Page(page.page(), page.year(), page.month(),
                        deduplicateFields(fields), deduplicateBases(bases)));
                return;
            }
        }
        result.add(page);
    }

    private Competence findCompetence(String text) {
        for (String line : text.split("\\R")) {
            String normalized = normalize(line);
            if (normalized.contains("competencia") || normalized.contains("referencia")
                    || normalized.contains("periodo") || normalized.contains("folha")
                    || normalized.contains("mes/ano")) {
                Matcher matcher = COMPETENCE.matcher(line);
                if (matcher.find()) return safeCompetence(matcher.group(1), matcher.group(2));
            }
        }
        for (String line : text.split("\\R")) {
            String normalized = normalize(line);
            for (Map.Entry<String, String> month : MONTHS.entrySet()) {
                if (!normalized.matches(".*\\b" + month.getKey() + "\\b.*")) continue;
                Matcher year = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)").matcher(line);
                if (year.find()) return new Competence(year.group(1), month.getValue());
            }
        }
        return new Competence("", "");
    }

    private Competence safeCompetence(String monthRaw, String yearRaw) {
        String month = monthRaw;
        if (!month.contains("?")) {
            int numeric = Integer.parseInt(month);
            month = numeric >= 1 && numeric <= 12 ? String.format("%02d", numeric) : "?".repeat(month.length());
        } else if (month.length() == 1) {
            month = "0?";
        }
        return new Competence(yearRaw, month);
    }

    private Field extractField(String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) return null;
        List<MatchValue> amounts = amounts(line);
        if (amounts.isEmpty()) return null;

        Matcher codeMatcher = CODE.matcher(line);
        String code = codeMatcher.find() ? codeMatcher.group(1) : "";
        int labelStart = codeMatcher.find(0) ? codeMatcher.end() : 0;
        int labelEnd = amounts.get(0).start();
        if (labelEnd <= labelStart) return null;
        String label = cleanLabel(line.substring(labelStart, labelEnd));
        String textReference = "";
        Matcher referenceMatcher = TEXT_REFERENCE.matcher(label);
        if (referenceMatcher.matches()) {
            label = cleanLabel(referenceMatcher.group(1));
            textReference = referenceMatcher.group(2).strip();
        }
        if (label.isBlank()) return null;

        String reference = amounts.size() >= 2
                ? TokenSanitizer.safeBrazilianNumber(amounts.get(0).value()) : textReference;
        String value = TokenSanitizer.safeBrazilianNumber(amounts.get(amounts.size() - 1).value());
        return new Field(code, label, reference, value);
    }

    private List<Field> extractParallelFields(String line) {
        List<MatchValue> values = amounts(line);
        if (values.isEmpty()) return List.of();
        List<Field> result = new ArrayList<>();

        String leftLabel = cleanLabel(line.substring(0, values.get(0).start()));
        if (!leftLabel.isBlank()) {
            result.add(new Field("", leftLabel, "",
                    TokenSanitizer.safeBrazilianNumber(values.get(0).value())));
        }
        if (values.size() >= 2) {
            String rightLabel = cleanLabel(line.substring(values.get(0).end(), values.get(1).start()));
            if (!rightLabel.isBlank()) {
                result.add(new Field("", rightLabel, "",
                        TokenSanitizer.safeBrazilianNumber(values.get(1).value())));
            }
        }
        return result;
    }

    private String cleanLabel(String value) {
        return value.strip().replaceAll("^[|;:.-]+", "").replaceAll("[|;:.-]+$", "").strip();
    }

    private List<MatchValue> amounts(String line) {
        List<MatchValue> values = new ArrayList<>();
        Matcher matcher = MONEY.matcher(line);
        while (matcher.find()) values.add(new MatchValue(matcher.start(1), matcher.end(1), matcher.group(1)));
        return values;
    }

    private boolean mentionsParallelColumns(String normalized) {
        return normalized.contains("proventos") && normalized.contains("descontos")
                && !normalized.contains("descricao");
    }

    private boolean isTableHeader(String normalized) {
        return ((normalized.contains("codigo") || normalized.matches(".*\\bcod\\.?(?: |$).*")
                || normalized.startsWith("verba "))
                && (normalized.contains("descricao") || normalized.contains("nome"))
                && (normalized.contains("valor") || normalized.contains("proventos")
                || normalized.contains("vencimentos") || normalized.contains("descontos")))
                || count(normalized, "descricao") >= 2;
    }

    private boolean isTableBoundary(String normalized) {
        return normalized.matches("^[t]?[o]tal(?: |$).*")
                || normalized.startsWith("liquido")
                || normalized.startsWith("valor liquido")
                || normalized.startsWith("base ")
                || normalized.startsWith("remuneracao funcao")
                || normalized.startsWith("adiantamento 13")
                || normalized.startsWith("provisao fgts")
                || normalized.startsWith("proventos retidos");
    }

    private void extractGenericTotal(
            String line,
            String normalized,
            List<MatchValue> amounts,
            List<Base> output) {
        if (!normalized.matches("^total(?: |$).*") || normalized.contains("desconto")
                || normalized.contains("provento") || amounts.isEmpty()) return;
        output.add(new Base("Total Vencimentos", TokenSanitizer.safeBrazilianNumber(amounts.get(0).value())));
        if (amounts.size() >= 2) {
            output.add(new Base("Total Descontos", TokenSanitizer.safeBrazilianNumber(amounts.get(1).value())));
        }
    }

    private List<BaseOccurrence> baseOccurrences(String line) {
        List<BaseOccurrence> found = new ArrayList<>();
        for (BasePattern basePattern : BASE_PATTERNS) {
            Matcher matcher = basePattern.pattern().matcher(line);
            while (matcher.find()) {
                found.add(new BaseOccurrence(matcher.start(), matcher.end(), basePattern.label()));
            }
        }
        found.sort(Comparator.comparingInt(BaseOccurrence::start)
                .thenComparing(Comparator.comparingInt(BaseOccurrence::end).reversed()));
        List<BaseOccurrence> withoutOverlaps = new ArrayList<>();
        for (BaseOccurrence occurrence : found) {
            boolean overlaps = withoutOverlaps.stream().anyMatch(existing ->
                    occurrence.start() < existing.end() && occurrence.end() > existing.start());
            if (!overlaps) withoutOverlaps.add(occurrence);
        }
        return withoutOverlaps.stream().sorted(Comparator.comparingInt(BaseOccurrence::start)).toList();
    }

    private void extractBases(String line, List<BaseOccurrence> occurrences, List<Base> output) {
        for (int index = 0; index < occurrences.size(); index++) {
            BaseOccurrence occurrence = occurrences.get(index);
            int limit = index + 1 < occurrences.size() ? occurrences.get(index + 1).start() : line.length();
            Matcher amount = MONEY.matcher(line);
            amount.region(occurrence.end(), limit);
            if (amount.find()) {
                output.add(new Base(occurrence.label(), TokenSanitizer.safeBrazilianNumber(amount.group(1))));
            }
        }
    }

    private List<Field> deduplicateFields(List<Field> fields) {
        Set<Field> unique = new LinkedHashSet<>(fields);
        return List.copyOf(unique);
    }

    private List<Base> deduplicateBases(List<Base> bases) {
        Set<Base> unique = new LinkedHashSet<>(bases);
        return List.copyOf(unique);
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static BasePattern base(String regex, String label) {
        return new BasePattern(Pattern.compile(regex), label);
    }

    private static Map<String, String> monthNames() {
        Map<String, String> months = new LinkedHashMap<>();
        String[] names = {"janeiro", "fevereiro", "marco", "abril", "maio", "junho",
                "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"};
        for (int index = 0; index < names.length; index++) months.put(names[index], String.format("%02d", index + 1));
        return months;
    }

    private static Map<String, String> financialMonthNames() {
        Map<String, String> months = new LinkedHashMap<>();
        String[] names = {"jan", "fev", "mar", "abr", "mai", "jun",
                "jul", "ago", "set", "out", "nov", "dez"};
        for (int index = 0; index < names.length; index++) months.put(names[index], String.format("%02d", index + 1));
        return months;
    }

    private record Competence(String year, String month) {}
    private record MatchValue(int start, int end, String value) {}
    private record BasePattern(Pattern pattern, String label) {}
    private record BaseOccurrence(int start, int end, String label) {}
    private record FinancialPeriod(int startYear, int endYear) {
        private boolean isKnown() { return startYear >= 1900 && endYear >= startYear; }
    }
    private record FinancialSection(int page, Competence competence, List<Field> fields, List<Base> bases) {
        private FinancialSection(int page, Competence competence) {
            this(page, competence, new ArrayList<>(), new ArrayList<>());
        }
    }
}
