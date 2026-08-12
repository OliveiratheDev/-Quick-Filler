package br.com.quickfiller.api;

import br.com.quickfiller.api.TranscriptionDtos.CreatedResponse;
import br.com.quickfiller.api.TranscriptionDtos.JobResponse;
import br.com.quickfiller.api.TranscriptionDtos.UpdateRequest;
import br.com.quickfiller.application.TranscriptionLifecycleService;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.export.ExportedFile;
import br.com.quickfiller.export.TranscriptionExportService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/transcricoes")
public class TranscriptionController {
    private final TranscriptionLifecycleService service;
    private final TranscriptionExportService exportService;

    public TranscriptionController(
            TranscriptionLifecycleService service,
            TranscriptionExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<CreatedResponse> create(
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam("tipo") String tipo) {
        DocumentType documentType;
        try {
            documentType = DocumentType.from(tipo);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        String id = service.create(arquivo, documentType);
        return ResponseEntity.accepted()
                .location(URI.create("/api/transcricoes/" + id))
                .body(new CreatedResponse(id));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable String id) { return service.get(id); }

    @PutMapping("/{id}")
    public ResponseEntity<Void> replace(@PathVariable String id, @RequestBody UpdateRequest request) {
        service.replace(id, request == null ? null : request.value());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/planilha")
    public ResponseEntity<byte[]> download(
            @PathVariable String id,
            @RequestParam(value = "formato", defaultValue = "xlsx") String formato) {
        ExportedFile file = exportService.export(id, formato);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }
}
