package br.com.quickfiller.infrastructure.storage;

import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.JobStatus;
import java.nio.file.Path;
import java.time.Instant;

public final class StoredJob {
    private final String id;
    private final DocumentType type;
    private final Path pdfPath;
    private final Instant createdAt;
    private JobStatus status;
    private String error;
    private Object value;
    private Instant updatedAt;

    public StoredJob(String id, DocumentType type, Path pdfPath, Instant now) {
        this.id = id;
        this.type = type;
        this.pdfPath = pdfPath;
        this.createdAt = now;
        this.updatedAt = now;
        this.status = JobStatus.PROCESSING;
    }

    public String id() { return id; }
    public DocumentType type() { return type; }
    public Path pdfPath() { return pdfPath; }
    public Instant createdAt() { return createdAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized JobStatus status() { return status; }
    public synchronized String error() { return error; }
    public synchronized Object value() { return value; }

    public synchronized void complete(Object newValue, Instant now) {
        if (status != JobStatus.PROCESSING) return;
        value = newValue;
        error = null;
        status = JobStatus.COMPLETED;
        updatedAt = now;
    }

    public synchronized void fail(String message, Instant now) {
        if (status != JobStatus.PROCESSING) return;
        value = null;
        error = message;
        status = JobStatus.ERROR;
        updatedAt = now;
    }

    public synchronized void replaceValue(Object newValue, Instant now) {
        if (status != JobStatus.COMPLETED) {
            throw new IllegalStateException("a transcrição ainda não foi concluída");
        }
        value = newValue;
        error = null;
        updatedAt = now;
    }
}
