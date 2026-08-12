package br.com.quickfiller.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.quickfiller.application.TranscriptionLifecycleService;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription.PunchKind;
import br.com.quickfiller.infrastructure.storage.StoredJob;
import br.com.quickfiller.warnings.WarningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TranscriptionExportServiceTest {

    @Test
    void payslipUsesDistinctFieldLabelsInFirstAppearanceOrderAndExcludesBases() throws Exception {
        PayslipTranscription value = new PayslipTranscription(List.of(
                new PayslipTranscription.Page(1, "2024", "01", List.of(
                        field("B", "2,00"), field("A", "1,00")),
                        List.of(new PayslipTranscription.Base("Base INSS", "3,00"))),
                new PayslipTranscription.Page(2, "2024", "02", List.of(
                        field("A", "4,00"), field("C", "5,00")), List.of())));
        ExportedFile exported = service(job(DocumentType.PAYSLIP, value)).export("job", "xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.bytes()))) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Pág.");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("B");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("A");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("C");
            assertThat(header.getCell(6)).isNull();
            assertThat(header.getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex())
                    .isEqualTo("FF173772");
        }
    }

    @Test
    void xlsxAppliesYellowAndRedPrecedenceWithLeftBorder() throws Exception {
        TimecardTranscription value = new TimecardTranscription(List.of(
                new TimecardTranscription.Page(1, List.of(
                        new TimecardTranscription.Day("01/01/2024", List.of(
                                new TimecardTranscription.Punch(PunchKind.IN, "08:00", "08:00"))),
                        new TimecardTranscription.Day("03/01/2024", List.of(
                                new TimecardTranscription.Punch(PunchKind.IN, "0?:00", "0?:00")))))));
        ExportedFile exported = service(job(DocumentType.TIMECARD, value)).export("job", "xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.bytes()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex())
                    .isEqualTo("FFFFF3CD");
            assertThat(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex())
                    .isEqualTo("FFF8D7DA");
            assertThat(sheet.getRow(2).getCell(0).getCellStyle().getBorderLeft()).isEqualTo(BorderStyle.MEDIUM);
        }
    }

    private TranscriptionExportService service(StoredJob job) {
        TranscriptionLifecycleService lifecycle = mock(TranscriptionLifecycleService.class);
        when(lifecycle.requireCompleted("job")).thenReturn(job);
        return new TranscriptionExportService(lifecycle, new WarningService(), new ObjectMapper());
    }

    private StoredJob job(DocumentType type, Object value) {
        StoredJob job = new StoredJob("job", type, Path.of("document.pdf"), Instant.EPOCH);
        job.complete(value, Instant.EPOCH);
        return job;
    }

    private PayslipTranscription.Field field(String label, String value) {
        return new PayslipTranscription.Field("", label, "", value);
    }
}
