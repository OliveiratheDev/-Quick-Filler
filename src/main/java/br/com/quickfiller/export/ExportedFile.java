package br.com.quickfiller.export;

public record ExportedFile(byte[] bytes, String contentType, String filename) {}
