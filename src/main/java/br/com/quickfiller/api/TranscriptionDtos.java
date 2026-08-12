package br.com.quickfiller.api;

import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.JobStatus;
import com.fasterxml.jackson.databind.JsonNode;

public final class TranscriptionDtos {
    private TranscriptionDtos() {}

    public record CreatedResponse(String id) {}

    public record JobResponse(
            String id,
            DocumentType tipo,
            JobStatus status,
            String erro,
            Object value) {}

    public record UpdateRequest(JsonNode value) {}

    public record ErrorResponse(String erro) {}
}
