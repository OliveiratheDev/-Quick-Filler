package br.com.quickfiller.application;

import br.com.quickfiller.api.ApiException;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class TranscriptionValidator {
    private TranscriptionValidator() {}

    public static void validateJsonTypes(DocumentType type, JsonNode value) {
        JsonNode pages = requiredArray(value, "pages", "value.pages");
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JsonNode page = requiredObject(pages.get(pageIndex), "value.pages[" + pageIndex + "]");
            requiredInteger(page, "page", "value.pages[" + pageIndex + "].page");
            if (type == DocumentType.TIMECARD) validateTimecardJson(page, pageIndex);
            else validatePayslipJson(page, pageIndex);
        }
    }

    public static void validate(DocumentType type, Object value) {
        if (type == DocumentType.TIMECARD && value instanceof TimecardTranscription timecard) {
            validateTimecard(timecard);
            return;
        }
        if (type == DocumentType.PAYSLIP && value instanceof PayslipTranscription payslip) {
            validatePayslip(payslip);
            return;
        }
        invalid("tipo de value incompatível com a transcrição");
    }

    private static void validateTimecard(TimecardTranscription value) {
        Set<Integer> pages = new HashSet<>();
        for (TimecardTranscription.Page page : value.pages()) {
            if (page == null || page.page() < 1 || !pages.add(page.page())) invalid("page deve começar em 1 e não se repetir");
            for (TimecardTranscription.Day day : page.days()) {
                if (day == null) invalid("days não pode conter null");
                for (int index = 0; index < day.punches().size(); index++) {
                    TimecardTranscription.Punch punch = day.punches().get(index);
                    if (punch == null || punch.kind() == null) invalid("punch inválido");
                    TimecardTranscription.PunchKind expected = index % 2 == 0
                            ? TimecardTranscription.PunchKind.IN
                            : TimecardTranscription.PunchKind.OUT;
                    if (punch.kind() != expected) invalid("kind deve alternar IN/OUT pela ordem");
                }
            }
        }
    }

    private static void validatePayslip(PayslipTranscription value) {
        Set<String> baseNames = Set.of(
                "base inss", "base de inss", "base ir", "base de ir", "base irrf", "base de irrf",
                "fgts", "total vencimentos", "total de vencimentos", "valor líquido", "valor liquido");
        int previousPage = 0;
        for (PayslipTranscription.Page page : value.pages()) {
            if (page == null || page.page() < 1 || page.page() < previousPage) {
                invalid("page deve começar em 1 e preservar a ordem do PDF");
            }
            previousPage = page.page();
            if (!page.month().isBlank() && !page.month().contains("?")
                    && !page.month().matches("0[1-9]|1[0-2]")) {
                invalid("month legível deve estar entre 01 e 12");
            }
            for (PayslipTranscription.Field field : page.fields()) {
                if (field == null || field.label().isBlank()) invalid("field deve possuir label");
                if (baseNames.contains(field.label().strip().toLowerCase(java.util.Locale.ROOT))) {
                    invalid("bases e totais não podem ser persistidos em fields");
                }
            }
            for (PayslipTranscription.Base base : page.bases()) {
                if (base == null || base.label().isBlank()) invalid("base deve possuir label");
            }
        }
    }

    private static void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static void validateTimecardJson(JsonNode page, int pageIndex) {
        String prefix = "value.pages[" + pageIndex + "]";
        JsonNode days = requiredArray(page, "days", prefix + ".days");
        for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
            JsonNode day = requiredObject(days.get(dayIndex), prefix + ".days[" + dayIndex + "]");
            String dayPrefix = prefix + ".days[" + dayIndex + "]";
            requiredText(day, "date_raw", dayPrefix + ".date_raw");
            JsonNode punches = requiredArray(day, "punches", dayPrefix + ".punches");
            for (int punchIndex = 0; punchIndex < punches.size(); punchIndex++) {
                JsonNode punch = requiredObject(punches.get(punchIndex), dayPrefix + ".punches[" + punchIndex + "]");
                String punchPrefix = dayPrefix + ".punches[" + punchIndex + "]";
                requiredText(punch, "kind", punchPrefix + ".kind");
                requiredText(punch, "time_raw", punchPrefix + ".time_raw");
                requiredText(punch, "time_hhmm", punchPrefix + ".time_hhmm");
            }
        }
    }

    private static void validatePayslipJson(JsonNode page, int pageIndex) {
        String prefix = "value.pages[" + pageIndex + "]";
        requiredText(page, "year", prefix + ".year");
        requiredText(page, "month", prefix + ".month");
        JsonNode fields = requiredArray(page, "fields", prefix + ".fields");
        for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
            JsonNode field = requiredObject(fields.get(fieldIndex), prefix + ".fields[" + fieldIndex + "]");
            String fieldPrefix = prefix + ".fields[" + fieldIndex + "]";
            requiredText(field, "code", fieldPrefix + ".code");
            requiredText(field, "label", fieldPrefix + ".label");
            requiredText(field, "reference", fieldPrefix + ".reference");
            requiredText(field, "value", fieldPrefix + ".value");
        }
        JsonNode bases = requiredArray(page, "bases", prefix + ".bases");
        for (int baseIndex = 0; baseIndex < bases.size(); baseIndex++) {
            JsonNode base = requiredObject(bases.get(baseIndex), prefix + ".bases[" + baseIndex + "]");
            String basePrefix = prefix + ".bases[" + baseIndex + "]";
            requiredText(base, "label", basePrefix + ".label");
            requiredText(base, "value", basePrefix + ".value");
        }
    }

    private static JsonNode requiredObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) invalid(path + " deve ser um objeto");
        return node;
    }

    private static JsonNode requiredArray(JsonNode object, String field, String path) {
        JsonNode node = object == null ? null : object.get(field);
        if (node == null || !node.isArray()) invalid(path + " deve ser uma lista");
        return node;
    }

    private static void requiredText(JsonNode object, String field, String path) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) invalid(path + " deve ser String");
    }

    private static void requiredInteger(JsonNode object, String field, String path) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber()) invalid(path + " deve ser inteiro");
    }
}
