package br.com.quickfiller.extraction;

import br.com.quickfiller.domain.DocumentType;
import java.util.List;

public interface TranscriptionExtractor {
    DocumentType supports();
    Object extract(List<PageText> pages);
}
