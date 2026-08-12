package br.com.quickfiller.extraction;

import br.com.quickfiller.application.ExtractionException;
import br.com.quickfiller.config.AppProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfPageTextSource {
    private final AppProperties properties;

    public PdfPageTextSource(AppProperties properties) { this.properties = properties; }

    public List<PageText> read(Path pdf) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                int pageNumber = index + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String embedded = stripper.getText(document);
                if (meaningfulCharacters(embedded) >= properties.getPdfMinTextCharacters()) {
                    pages.add(new PageText(pageNumber, embedded, PageText.Source.EMBEDDED));
                } else {
                    pages.add(new PageText(pageNumber, ocr(renderer, index, pdf.getParent(), pageNumber),
                            PageText.Source.OCR));
                }
            }
        }
        return List.copyOf(pages);
    }

    private long meaningfulCharacters(String text) {
        if (text == null) return 0;
        return text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private String ocr(PDFRenderer renderer, int pageIndex, Path jobDir, int pageNumber) {
        Path ocrDir = jobDir.resolve("ocr-" + pageNumber);
        try {
            Files.createDirectories(ocrDir);
            Path imagePath = ocrDir.resolve("page.png");
            Path outputBase = ocrDir.resolve("result");
            Path diagnostic = ocrDir.resolve("diagnostic.txt");
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, properties.getOcrDpi(), ImageType.RGB);
            if (!ImageIO.write(image, "png", imagePath.toFile())) {
                throw new ExtractionException("não foi possível preparar a página " + pageNumber + " para OCR");
            }

            ProcessBuilder builder = new ProcessBuilder(
                    properties.getTesseractCommand(),
                    imagePath.toString(),
                    outputBase.toString(),
                    "-l", properties.getTesseractLanguage(),
                    "--psm", "6");
            builder.redirectError(diagnostic.toFile());
            builder.redirectOutput(diagnostic.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new ExtractionException(
                        "página " + pageNumber + " não possui texto e o OCR não está disponível", exception);
            }
            boolean finished = process.waitFor(properties.getOcrTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ExtractionException("OCR excedeu o tempo limite na página " + pageNumber);
            }
            Path result = Path.of(outputBase + ".txt");
            if (process.exitValue() != 0 || !Files.exists(result)) {
                throw new ExtractionException("OCR falhou na página " + pageNumber);
            }
            return Files.readString(result, StandardCharsets.UTF_8);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("processamento interrompido na página " + pageNumber, exception);
        } catch (IOException exception) {
            throw new ExtractionException("não foi possível aplicar OCR na página " + pageNumber, exception);
        } finally {
            deleteDirectory(ocrDir);
        }
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
