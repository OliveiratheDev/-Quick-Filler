package br.com.quickfiller.export;

import br.com.quickfiller.api.ApiException;
import br.com.quickfiller.application.TranscriptionLifecycleService;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.infrastructure.storage.StoredJob;
import br.com.quickfiller.warnings.WarningService;
import br.com.quickfiller.warnings.WarningService.Level;
import br.com.quickfiller.warnings.WarningService.RowWarning;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionExportService {
    private static final String XLSX_CONTENT = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String JSON_CONTENT = "application/json;charset=UTF-8";
    private static final String CSV_CONTENT = "text/csv;charset=UTF-8";

    private final TranscriptionLifecycleService lifecycle;
    private final WarningService warningService;
    private final ObjectMapper mapper;

    public TranscriptionExportService(
            TranscriptionLifecycleService lifecycle,
            WarningService warningService,
            ObjectMapper mapper) {
        this.lifecycle = lifecycle;
        this.warningService = warningService;
        this.mapper = mapper;
    }

    public ExportedFile export(String id, String requestedFormat) {
        StoredJob job = lifecycle.requireCompleted(id);
        Format format = Format.from(requestedFormat);
        String baseName = job.type().value() + "-" + job.id();
        try {
            return switch (format) {
                case XLSX -> new ExportedFile(xlsx(job), XLSX_CONTENT, baseName + ".xlsx");
                case CSV -> new ExportedFile(csv(job), CSV_CONTENT, baseName + ".csv");
                case JSON -> new ExportedFile(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(job.value()),
                        JSON_CONTENT, baseName + ".json");
            };
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "não foi possível gerar a planilha");
        }
    }

    private byte[] xlsx(StoredJob job) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            if (job.type() == DocumentType.TIMECARD) {
                writeTimecard(workbook, (TimecardTranscription) job.value(), styles);
            } else {
                writePayslip(workbook, (PayslipTranscription) job.value(), styles);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeTimecard(XSSFWorkbook workbook, TimecardTranscription value, Styles styles) {
        Sheet sheet = workbook.createSheet("Cartão de ponto");
        int maxPunches = value.pages().stream().flatMap(page -> page.days().stream())
                .mapToInt(day -> day.punches().size()).max().orElse(0);
        int pairCount = (maxPunches + 1) / 2;
        List<String> headers = new ArrayList<>();
        headers.add("Data");
        for (int pair = 1; pair <= pairCount; pair++) {
            headers.add("Entrada " + pair);
            headers.add("Saída " + pair);
        }
        writeHeader(sheet, headers, styles.header());
        List<RowWarning> warnings = warningService.timecard(value);
        int rowIndex = 1;
        int warningIndex = 0;
        for (TimecardTranscription.Page page : value.pages()) {
            for (TimecardTranscription.Day day : page.days()) {
                Row row = sheet.createRow(rowIndex++);
                RowWarning warning = warnings.get(warningIndex++);
                setString(row.createCell(0), day.dateRaw());
                for (int index = 0; index < day.punches().size(); index++) {
                    setString(row.createCell(index + 1), day.punches().get(index).timeHhmm());
                }
                fillMissing(row, headers.size());
                styleDataRow(row, headers.size(), warning, styles);
            }
        }
        finishSheet(sheet, headers.size());
    }

    private void writePayslip(XSSFWorkbook workbook, PayslipTranscription value, Styles styles) {
        Sheet sheet = workbook.createSheet("Holerites");
        Set<String> labels = new LinkedHashSet<>();
        value.pages().forEach(page -> page.fields().forEach(field -> labels.add(field.label())));
        List<String> headers = new ArrayList<>(List.of("Pág.", "Mês", "Ano"));
        headers.addAll(labels);
        writeHeader(sheet, headers, styles.header());
        List<RowWarning> warnings = warningService.payslip(value);
        for (int pageIndex = 0; pageIndex < value.pages().size(); pageIndex++) {
            PayslipTranscription.Page page = value.pages().get(pageIndex);
            Row row = sheet.createRow(pageIndex + 1);
            setString(row.createCell(0), Integer.toString(page.page()));
            setString(row.createCell(1), page.month());
            setString(row.createCell(2), page.year());
            Map<String, String> values = new LinkedHashMap<>();
            page.fields().forEach(field -> values.putIfAbsent(field.label(), field.value()));
            int column = 3;
            for (String label : labels) setString(row.createCell(column++), values.getOrDefault(label, ""));
            styleDataRow(row, headers.size(), warnings.get(pageIndex), styles);
        }
        finishSheet(sheet, headers.size());
    }

    private void writeHeader(Sheet sheet, List<String> headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = row.createCell(index);
            setString(cell, headers.get(index));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, Math.max(0, headers.size() - 1)));
    }

    private void styleDataRow(Row row, int width, RowWarning warning, Styles styles) {
        if (warning.level() == Level.NONE) return;
        for (int column = 0; column < width; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            cell.setCellStyle(warning.level() == Level.YELLOW
                    ? styles.yellow()
                    : (column == 0 ? styles.redFirst() : styles.red()));
        }
    }

    private void fillMissing(Row row, int width) {
        for (int column = 0; column < width; column++) row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    }

    private void finishSheet(Sheet sheet, int columns) {
        for (int column = 0; column < columns; column++) {
            sheet.autoSizeColumn(column);
            sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 50 * 256));
        }
    }

    private void setString(Cell cell, String value) { cell.setCellValue(value == null ? "" : value); }

    private byte[] csv(StoredJob job) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        if (job.type() == DocumentType.TIMECARD) writeTimecardCsv(csv, (TimecardTranscription) job.value());
        else writePayslipCsv(csv, (PayslipTranscription) job.value());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeTimecardCsv(StringBuilder output, TimecardTranscription value) {
        int maxPunches = value.pages().stream().flatMap(page -> page.days().stream())
                .mapToInt(day -> day.punches().size()).max().orElse(0);
        List<String> headers = new ArrayList<>(List.of("Data"));
        for (int pair = 1; pair <= (maxPunches + 1) / 2; pair++) {
            headers.add("Entrada " + pair);
            headers.add("Saída " + pair);
        }
        csvRow(output, headers);
        value.pages().forEach(page -> page.days().forEach(day -> {
            List<String> cells = new ArrayList<>();
            cells.add(day.dateRaw());
            day.punches().forEach(punch -> cells.add(punch.timeHhmm()));
            while (cells.size() < headers.size()) cells.add("");
            csvRow(output, cells);
        }));
    }

    private void writePayslipCsv(StringBuilder output, PayslipTranscription value) {
        Set<String> labels = new LinkedHashSet<>();
        value.pages().forEach(page -> page.fields().forEach(field -> labels.add(field.label())));
        List<String> headers = new ArrayList<>(List.of("Pág.", "Mês", "Ano"));
        headers.addAll(labels);
        csvRow(output, headers);
        value.pages().forEach(page -> {
            Map<String, String> values = new LinkedHashMap<>();
            page.fields().forEach(field -> values.putIfAbsent(field.label(), field.value()));
            List<String> cells = new ArrayList<>(List.of(Integer.toString(page.page()), page.month(), page.year()));
            labels.forEach(label -> cells.add(values.getOrDefault(label, "")));
            csvRow(output, cells);
        });
    }

    private void csvRow(StringBuilder output, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) output.append(';');
            String value = cells.get(index) == null ? "" : cells.get(index);
            if (value.indexOf(';') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                output.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else output.append(value);
        }
        output.append("\r\n");
    }

    enum Format {
        XLSX, CSV, JSON;

        static Format from(String value) {
            try { return Format.valueOf((value == null ? "xlsx" : value).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "formato deve ser xlsx, csv ou json");
            }
        }
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle yellow;
        private final CellStyle red;
        private final CellStyle redFirst;

        private Styles(XSSFWorkbook workbook) {
            XSSFColor headerColor = new XSSFColor(new byte[] {0x17, 0x37, 0x72}, null);
            XSSFColor yellowColor = new XSSFColor(new byte[] {(byte) 0xFF, (byte) 0xF3, (byte) 0xCD}, null);
            XSSFColor redColor = new XSSFColor(new byte[] {(byte) 0xF8, (byte) 0xD7, (byte) 0xDA}, null);
            XSSFColor borderColor = new XSSFColor(new byte[] {(byte) 0xDC, 0x35, 0x45}, null);

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(headerColor);
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            this.header = headerStyle;

            this.yellow = fillStyle(workbook, yellowColor);
            this.red = fillStyle(workbook, redColor);
            XSSFCellStyle first = fillStyle(workbook, redColor);
            first.setBorderLeft(BorderStyle.MEDIUM);
            first.setLeftBorderColor(borderColor);
            this.redFirst = first;
        }

        private static XSSFCellStyle fillStyle(XSSFWorkbook workbook, XSSFColor color) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }

        CellStyle header() { return header; }
        CellStyle yellow() { return yellow; }
        CellStyle red() { return red; }
        CellStyle redFirst() { return redFirst; }
    }
}
