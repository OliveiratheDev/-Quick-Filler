package br.com.quickfiller.application;

import br.com.quickfiller.api.ApiException;
import br.com.quickfiller.api.TranscriptionDtos.JobResponse;
import br.com.quickfiller.config.AppProperties;
import br.com.quickfiller.domain.DocumentType;
import br.com.quickfiller.domain.payslip.PayslipTranscription;
import br.com.quickfiller.domain.timecard.TimecardTranscription;
import br.com.quickfiller.extraction.ExtractionCoordinator;
import br.com.quickfiller.infrastructure.storage.InMemoryJobStore;
import br.com.quickfiller.infrastructure.storage.StoredJob;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TranscriptionLifecycleService {
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptionLifecycleService.class);
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final InMemoryJobStore store;
    private final ExtractionCoordinator coordinator;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final AppProperties properties;
    private final Clock clock;
    private final Path storageRoot;

    @Autowired
    public TranscriptionLifecycleService(
            InMemoryJobStore store,
            ExtractionCoordinator coordinator,
            ObjectMapper mapper,
            @Qualifier("transcriptionExecutor") Executor executor,
            AppProperties properties) throws IOException {
        this(store, coordinator, mapper, executor, properties, Clock.systemUTC());
    }

    TranscriptionLifecycleService(
            InMemoryJobStore store,
            ExtractionCoordinator coordinator,
            ObjectMapper mapper,
            Executor executor,
            AppProperties properties,
            Clock clock) throws IOException {
        this.store = store;
        this.coordinator = coordinator;
        this.mapper = mapper;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        Path configured = properties.getStorageDir();
        this.storageRoot = configured != null
                ? configured
                : Path.of(System.getProperty("java.io.tmpdir"), "quick-filler");
        Files.createDirectories(storageRoot);
    }

    public String create(MultipartFile upload, DocumentType type) {
        validateMetadata(upload);
        String id = UUID.randomUUID().toString();
        Path jobDir = storageRoot.resolve(id);
        Path pdf = jobDir.resolve("document.pdf");
        try {
            Files.createDirectories(jobDir);
            copyWithLimit(upload, pdf);
            validatePdf(pdf);
        } catch (ApiException exception) {
            deleteRecursively(jobDir);
            throw exception;
        } catch (Exception exception) {
            deleteRecursively(jobDir);
            throw new ApiException(HttpStatus.BAD_REQUEST, "arquivo PDF inválido ou corrompido");
        }

        StoredJob job = new StoredJob(id, type, pdf, clock.instant());
        store.put(job);
        try {
            executor.execute(() -> process(job));
        } catch (RejectedExecutionException exception) {
            store.remove(id);
            deleteRecursively(jobDir);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "capacidade de processamento temporariamente esgotada; tente novamente");
        }
        return id;
    }

    public JobResponse get(String id) {
        StoredJob job = store.require(id);
        return new JobResponse(job.id(), job.type(), job.status(), job.error(), job.value());
    }

    public void replace(String id, JsonNode valueNode) {
        if (valueNode == null || !valueNode.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "value deve ser um objeto JSON");
        }
        StoredJob job = store.require(id);
        Object value;
        try {
            TranscriptionValidator.validateJsonTypes(job.type(), valueNode);
            Class<?> target = job.type() == DocumentType.TIMECARD
                    ? TimecardTranscription.class
                    : PayslipTranscription.class;
            value = mapper.readerFor(target)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(valueNode);
            TranscriptionValidator.validate(job.type(), value);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "value não segue o formato literal de " + job.type().value());
        }
        try {
            job.replaceValue(value, clock.instant());
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    public StoredJob requireCompleted(String id) {
        StoredJob job = store.require(id);
        if (job.status() != br.com.quickfiller.domain.JobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "a transcrição ainda não foi concluída");
        }
        return job;
    }

    private void process(StoredJob job) {
        try {
            Object value = coordinator.extract(job.pdfPath(), job.type());
            TranscriptionValidator.validate(job.type(), value);
            job.complete(value, clock.instant());
        } catch (Exception exception) {
            LOG.error("Falha no job {} ({})", job.id(), exception.getClass().getSimpleName());
            job.fail(friendlyProcessingError(exception), clock.instant());
        }
    }

    private void validateMetadata(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "arquivo PDF é obrigatório");
        }
        String name = upload.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "a extensão do arquivo deve ser .pdf");
        }
        String contentType = upload.getContentType();
        if (!"application/pdf".equalsIgnoreCase(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "o MIME type deve ser application/pdf");
        }
        long limit = properties.getMaxUploadMb() * 1024L * 1024L;
        if (upload.getSize() > limit) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "arquivo excede o limite de " + properties.getMaxUploadMb() + " MB");
        }
    }

    private void copyWithLimit(MultipartFile upload, Path destination) throws IOException {
        long limit = properties.getMaxUploadMb() * 1024L * 1024L;
        try (InputStream input = upload.getInputStream();
             var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "arquivo excede o limite de " + properties.getMaxUploadMb() + " MB");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void validatePdf(Path pdf) throws IOException {
        byte[] first = new byte[PDF_MAGIC.length];
        try (InputStream input = Files.newInputStream(pdf)) {
            if (input.read(first) != PDF_MAGIC.length || !java.util.Arrays.equals(first, PDF_MAGIC)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "arquivo não possui assinatura PDF válida");
            }
        }
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() < 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PDF não possui páginas");
            }
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF protegido por senha não é suportado");
        }
    }

    private String friendlyProcessingError(Exception exception) {
        if (exception instanceof ExtractionException extractionException) {
            return extractionException.getMessage();
        }
        return "não foi possível processar o PDF; confirme se o documento está legível";
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void purgeExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(properties.getRetentionMinutes()));
        store.olderThan(cutoff).forEach(job -> {
            if (store.remove(job.id()) != null) {
                deleteRecursively(job.pdfPath().getParent());
            }
        });
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !directory.normalize().startsWith(storageRoot.normalize())) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
