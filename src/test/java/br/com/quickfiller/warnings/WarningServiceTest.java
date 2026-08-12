package br.com.quickfiller.warnings;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription.PunchKind;
import br.com.quickfiller.warnings.WarningService.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarningServiceTest {
    private final WarningService service = new WarningService();

    @Test
    void oddPunchesAreYellowAndNonSequentialDatesAreRed() {
        TimecardTranscription value = new TimecardTranscription(List.of(
                new TimecardTranscription.Page(1, List.of(
                        day("01/01/2024", 1),
                        day("03/01/2024", 2)))));

        assertThat(service.timecard(value).get(0).level()).isEqualTo(Level.YELLOW);
        assertThat(service.timecard(value).get(0).reasons()).contains("batidas ímpares");
        assertThat(service.timecard(value).get(1).level()).isEqualTo(Level.RED);
        assertThat(service.timecard(value).get(1).reasons()).contains("data não sequencial");
    }

    @Test
    void decemberToJanuaryWithUnreadablePageBetweenIsSequential() {
        PayslipTranscription value = new PayslipTranscription(List.of(
                page(1, "2023", "12", true),
                page(2, "????", "??", false),
                page(3, "2024", "01", true),
                page(4, "2024", "03", true)));

        List<WarningService.RowWarning> warnings = service.payslip(value);
        assertThat(warnings.get(2).level()).isEqualTo(Level.NONE);
        assertThat(warnings.get(3).level()).isEqualTo(Level.RED);
        assertThat(warnings.get(3).reasons()).contains("mês não sequencial");
        assertThat(warnings.get(1).reasons()).contains("página vazia", "leitura contém ?");
    }

    private TimecardTranscription.Day day(String date, int punches) {
        return new TimecardTranscription.Day(date,
                java.util.stream.IntStream.range(0, punches)
                        .mapToObj(index -> new TimecardTranscription.Punch(
                                index % 2 == 0 ? PunchKind.IN : PunchKind.OUT, "08:00", "08:00"))
                        .toList());
    }

    private PayslipTranscription.Page page(int number, String year, String month, boolean withData) {
        List<PayslipTranscription.Field> fields = withData
                ? List.of(new PayslipTranscription.Field("1", "Salário", "", "1.000,00")) : List.of();
        return new PayslipTranscription.Page(number, year, month, fields, List.of());
    }
}
