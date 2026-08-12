package br.com.quickfiller.extraction;

import br.com.quickfiller.domain.DocumentType;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ExtractionCoordinator {
    private final PdfPageTextSource pageTextSource;
    private final Map<DocumentType, TranscriptionExtractor> extractors;

    public ExtractionCoordinator(PdfPageTextSource pageTextSource, List<TranscriptionExtractor> extractors) {
        this.pageTextSource = pageTextSource;
        this.extractors = new EnumMap<>(DocumentType.class);
        extractors.forEach(extractor -> this.extractors.put(extractor.supports(), extractor));
    }

    public Object extract(Path pdf, DocumentType type) throws Exception {
        TranscriptionExtractor extractor = extractors.get(type);
        if (extractor == null) throw new IllegalStateException("extrator não configurado");
        return extractor.extract(pageTextSource.read(pdf));
    }
}
