package br.com.quickfiller.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.extraction.payslip.PayslipExtractor;
import br.com.quickfiller.extraction.timecard.TimecardExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractorTest {

    @Test
    void timecardKeepsDocumentAndPunchOrder() {
        String text = "21/05/2019 08:25 12:00 13:00 18:25\n"
                + "25/05/2019\n"
                + "38/05/2019 8h05";
        TimecardTranscription result = new TimecardExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.EMBEDDED)));

        assertThat(result.pages().get(0).days()).extracting(TimecardTranscription.Day::dateRaw)
                .containsExactly("21/05/2019", "25/05/2019", "??/05/2019");
        assertThat(result.pages().get(0).days().get(0).punches())
                .extracting(punch -> punch.kind().name())
                .containsExactly("IN", "OUT", "IN", "OUT");
        assertThat(result.pages().get(0).days().get(2).punches().get(0).timeHhmm()).isEqualTo("08:05");
    }

    @Test
    void timecardBuildsDateFromHeaderSkipsScheduledJornadaAndMergesSplitPunches() {
        String text = "Mes/Ano : 7 / 2012 Tipo de Jornada: FLEXIVEL\n"
                + "Dia Semana Jornada Entrada Saida Ocorrencia Qtde\n"
                + "1 - DOM 08:00\n"
                + "17 - TER 08:00 09:09 13:01 HE-BCO DE HORAS 00:13\n"
                + "17 - TER 08:00 14:16 18:50 HE-REMUNERADA 00:13";

        TimecardTranscription result = new TimecardExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.EMBEDDED)));

        assertThat(result.pages().get(0).days()).extracting(TimecardTranscription.Day::dateRaw)
                .containsExactly("01/07/2012", "17/07/2012");
        assertThat(result.pages().get(0).days().get(0).punches()).isEmpty();
        assertThat(result.pages().get(0).days().get(1).punches())
                .extracting(TimecardTranscription.Punch::timeHhmm)
                .containsExactly("09:09", "13:01", "14:16", "18:50");
    }

    @Test
    void timecardOnlyAcceptsFullDatesAtLineStartAndIgnoresAdministrativeTimes() {
        String text = "Período: 14/12/2019 a 05/04/2025\n"
                + "Data: 09/03/2026\n"
                + "16/12/2019 SEG 07:00d 12:00d 13:00d 17:00d | 01:00";

        TimecardTranscription result = new TimecardExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.OCR)));

        assertThat(result.pages().get(0).days()).hasSize(1);
        assertThat(result.pages().get(0).days().get(0).punches())
                .extracting(TimecardTranscription.Punch::timeHhmm)
                .containsExactly("07:00", "12:00", "13:00", "17:00");
    }

    @Test
    void payslipSeparatesMainFieldsFromBasesAndTotals() {
        String text = "Competência: 01/2020\n"
                + "Código Descrição Ref Vencimentos Descontos\n"
                + "0010 Salário Base 220,00 2.389,77\n"
                + "0998 INSS 262,87\n"
                + "Base INSS 2.545,68\n"
                + "Total Vencimentos 2.545,68\n"
                + "Valor Líquido 2.282,81";
        PayslipTranscription result = new PayslipExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.EMBEDDED)));

        PayslipTranscription.Page page = result.pages().get(0);
        assertThat(page.year()).isEqualTo("2020");
        assertThat(page.month()).isEqualTo("01");
        assertThat(page.fields()).extracting(PayslipTranscription.Field::label)
                .containsExactly("Salário Base", "INSS");
        assertThat(page.bases()).extracting(PayslipTranscription.Base::label)
                .containsExactly("Base INSS", "Total Vencimentos", "Valor Líquido");
        assertThat(page.fields()).noneMatch(field -> field.label().startsWith("Base")
                || field.label().equals("Valor Líquido"));
        assertThat(page.fields().get(0).reference()).isEqualTo("220,00");
        assertThat(page.fields().get(0).value()).isEqualTo("2.389,77");
    }

    @Test
    void payslipScopesFieldsToTablesAndPreservesNegativeValues() {
        String text = "Mês/Ano: 08/2018 Folha de Pagamento: MÊS\n"
                + "Verba Nome Base / Saldo / Benefício Valor\n"
                + "010 VENCIMENTO PADRAO-VP 3.059,94\n"
                + "803 PREVI PESSOAL PB2 6.188,63 -433,20\n"
                + "Remuneração Função Vl. Ref.: 5.017,04 Proventos Bruto: 6.188,63\n"
                + "Provisão FGTS: 495,09";

        PayslipTranscription.Page page = new PayslipExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.EMBEDDED))).pages().get(0);

        assertThat(page.fields()).extracting(PayslipTranscription.Field::label)
                .containsExactly("VENCIMENTO PADRAO-VP", "PREVI PESSOAL PB2");
        assertThat(page.fields().get(1).reference()).isEqualTo("6.188,63");
        assertThat(page.fields().get(1).value()).isEqualTo("-433,20");
        assertThat(page.fields()).noneMatch(field -> field.label().contains("Remuneração Função"));
    }

    @Test
    void payslipSplitsParallelEarningsAndDiscountColumns() {
        String text = "CNPJ: SETEMBRO/2019 MENSAL 1/1\n"
                + "Proventos Descontos\n"
                + "Descrição Qtde Valor Descrição Qtde Valor\n"
                + "SALARIO 953,36 INSS MES 200,43\n"
                + "REMUNERACAO VARIAVEL 1.100,00 VALE REFEICAO 6,00\n"
                + "TOTAL DE PROVENTOS 2.053,36 TOTAL DE DESCONTOS 206,43\n"
                + "LIQUIDO A RECEBER 1.846,93";

        PayslipTranscription.Page page = new PayslipExtractor().extract(
                List.of(new PageText(1, text, PageText.Source.OCR))).pages().get(0);

        assertThat(page.month()).isEqualTo("09");
        assertThat(page.year()).isEqualTo("2019");
        assertThat(page.fields()).extracting(PayslipTranscription.Field::label)
                .containsExactly("SALARIO", "INSS MES", "REMUNERACAO VARIAVEL", "VALE REFEICAO");
        assertThat(page.bases()).extracting(PayslipTranscription.Base::label)
                .contains("Total Vencimentos", "Total Descontos", "Valor Líquido");
    }

    @Test
    void payslipKeepsUnsupportedFinancialStatementPagesEmptyInsteadOfGuessing() {
        PayslipTranscription.Page page = new PayslipExtractor().extract(List.of(
                new PageText(1, "FICHAFINANCEIRA-PERIODO:2017/04 a 2025/03\nMês: abr-17",
                        PageText.Source.EMBEDDED))).pages().get(0);

        assertThat(page.year()).isEmpty();
        assertThat(page.month()).isEmpty();
        assertThat(page.fields()).isEmpty();
        assertThat(page.bases()).isEmpty();
    }
}
