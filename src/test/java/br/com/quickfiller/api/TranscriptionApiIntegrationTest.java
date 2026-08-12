package br.com.quickfiller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quickfiller.extraction.PageText;
import br.com.quickfiller.extraction.PdfPageTextSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.worker-threads=1",
        "app.worker-queue=5",
        "app.retention-minutes=5"
})
@AutoConfigureMockMvc
class TranscriptionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PdfPageTextSource pageTextSource;

    @Test
    void healthIsPublicAndPlain() throws Exception {
        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    void fullTimecardLifecycleUsesLiteralContractAndCorrectedDownload() throws Exception {
        when(pageTextSource.read(any())).thenReturn(List.of(
                new PageText(1, "01/01/2024 08:00 12:00\n02/01/2024 13:00 18:00", PageText.Source.EMBEDDED)));
        MockMultipartFile pdf = new MockMultipartFile(
                "arquivo", "ponto.pdf", "application/pdf", validPdf());

        MvcResult created = mvc.perform(multipart("/api/transcricoes")
                        .file(pdf).param("tipo", "cartao-ponto"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isString())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        JsonNode completed = awaitCompletion(id);
        org.assertj.core.api.Assertions.assertThat(completed.get("status").asText()).isEqualTo("concluido");
        org.assertj.core.api.Assertions.assertThat(completed.get("erro").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(completed.at("/value/pages/0/page").asInt()).isEqualTo(1);

        String corrected = """
                {"value":{"pages":[{"page":1,"days":[
                  {"date_raw":"01/01/2024","punches":[
                    {"kind":"IN","time_raw":"08:00","time_hhmm":"08:01"},
                    {"kind":"OUT","time_raw":"12:00","time_hhmm":"12:00"}
                  ]}
                ]}]}}
                """;
        mvc.perform(put("/api/transcricoes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(corrected))
                .andExpect(status().isNoContent());
        MvcResult download = mvc.perform(get("/api/transcricoes/{id}/planilha", id).param("formato", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(download.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void corruptPdfAndWrongMimeAreRejectedWithoutCreatingAJob() throws Exception {
        MockMultipartFile corrupt = new MockMultipartFile(
                "arquivo", "bad.pdf", "application/pdf", "%PDF-not-a-document".getBytes());
        mvc.perform(multipart("/api/transcricoes").file(corrupt).param("tipo", "holerite"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").isNotEmpty());

        MockMultipartFile wrongMime = new MockMultipartFile(
                "arquivo", "bad.pdf", "text/plain", validPdf());
        mvc.perform(multipart("/api/transcricoes").file(wrongMime).param("tipo", "holerite"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unknownJobAndInvalidTypeHaveDefinedErrors() throws Exception {
        mvc.perform(get("/api/transcricoes/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("transcrição não encontrada"));
        MockMultipartFile pdf = new MockMultipartFile(
                "arquivo", "document.pdf", "application/pdf", validPdf());
        mvc.perform(multipart("/api/transcricoes").file(pdf).param("tipo", "outro"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode awaitCompletion(String id) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mvc.perform(get("/api/transcricoes/{id}", id))
                    .andExpect(status().isOk()).andReturn();
            JsonNode job = mapper.readTree(result.getResponse().getContentAsString());
            if (!"processando".equals(job.get("status").asText())) return job;
            Thread.sleep(25);
        }
        throw new AssertionError("job não concluiu no tempo do teste");
    }

    private byte[] validPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
