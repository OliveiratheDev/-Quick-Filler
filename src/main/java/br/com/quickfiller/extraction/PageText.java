package br.com.quickfiller.extraction;

public record PageText(int page, String text, Source source) {
    public enum Source { EMBEDDED, OCR }
}
