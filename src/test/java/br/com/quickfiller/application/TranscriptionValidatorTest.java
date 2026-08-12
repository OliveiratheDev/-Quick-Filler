package br.com.quickfiller.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.quickfiller.api.ApiException;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void payslipMoneyMustBeAStringAndAllLiteralFieldsMustExist() throws Exception {
        var numericMoney = mapper.readTree("""
                {"pages":[{"page":1,"year":"2024","month":"01","fields":[
                  {"code":"0010","label":"Salário","reference":"220,00","value":2389.77}
                ],"bases":[]}]}
                """);
        assertThatThrownBy(() -> TranscriptionValidator.validateJsonTypes(DocumentType.PAYSLIP, numericMoney))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("value deve ser String");

        var missingRaw = mapper.readTree("""
                {"pages":[{"page":1,"days":[{"date_raw":"01/01/2024","punches":[
                  {"kind":"IN","time_hhmm":"08:00"}
                ]}]}]}
                """);
        assertThatThrownBy(() -> TranscriptionValidator.validateJsonTypes(DocumentType.TIMECARD, missingRaw))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("time_raw deve ser String");
    }

    @Test
    void payslipAllowsCompetencesSharingTheSamePhysicalPageButRejectsPageReordering() {
        var valid = new PayslipTranscription(List.of(
                new PayslipTranscription.Page(1, "2017", "04", List.of(), List.of()),
                new PayslipTranscription.Page(1, "2017", "05", List.of(), List.of()),
                new PayslipTranscription.Page(2, "2017", "06", List.of(), List.of())));
        assertThatCode(() -> TranscriptionValidator.validate(DocumentType.PAYSLIP, valid))
                .doesNotThrowAnyException();

        var reordered = new PayslipTranscription(List.of(
                new PayslipTranscription.Page(2, "2017", "06", List.of(), List.of()),
                new PayslipTranscription.Page(1, "2017", "07", List.of(), List.of())));
        assertThatThrownBy(() -> TranscriptionValidator.validate(DocumentType.PAYSLIP, reordered))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("preservar a ordem");
    }
}
