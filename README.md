# Quick Filler — desafio técnico

Aplicação web para o ciclo completo de cartão de ponto e holerite:

`upload do PDF → processamento assíncrono → revisão ao lado do PDF → download corrigido`

Há um pipeline compartilhado para upload, jobs, PDF/OCR, revisão e download. Somente os parsers e a disposição da planilha variam por tipo.

## Execução rápida

Pré-requisito: Docker com Docker Compose.

```bash
docker compose up --build
```

Abra [http://localhost:8080](http://localhost:8080). O healthcheck está em [http://localhost:8080/healthz](http://localhost:8080/healthz).

## Deploy no Render

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/OliveiratheDev/-Quick-Filler)

O `render.yaml` cria um Web Service Docker no plano gratuito, configura `/healthz`, limita a memória da JVM e reduz a concorrência do OCR para os recursos disponíveis. O serviço gratuito pode hibernar após inatividade e usa armazenamento efêmero; por isso os PDFs continuam sujeitos à retenção curta e também desaparecem em reinícios.

## Fluxo da interface

1. Escolha `cartao-ponto` ou `holerite` e envie um PDF.
2. O POST retorna `202` e a tela acompanha o job por polling.
3. Revise a tabela ao lado do PDF. Amarelo indica atenção; vermelho, quebra de sequência.
4. Salve as correções. O download faz o PUT pendente antes de gerar o arquivo.
5. Baixe XLSX, CSV ou JSON.

## API literal

```text
POST /api/transcricoes
GET  /api/transcricoes/{id}
PUT  /api/transcricoes/{id}
GET  /api/transcricoes/{id}/planilha?formato=xlsx|csv|json
GET  /healthz
```

Exemplo:

```bash
curl -i -F "arquivo=@documento.pdf;type=application/pdf" \
  -F "tipo=cartao-ponto" http://localhost:8080/api/transcricoes
```

O POST responde `202 Accepted` com `{ "id": "..." }`. Enquanto processa, o GET devolve `status: "processando"` e `value: null`; depois, `concluido` ou `erro` com mensagem legível.

## PDFs reais e cobertura observada

Os oito PDFs recebidos estão em `exemplos/`. Eles foram renderizados e inspecionados página por página, depois processados pela API real:

| Arquivo | Aquisição | Resultado bruto observado |
|---|---|---|
| `time-card-01.pdf` | texto | 153 dias; até 4 batidas |
| `time-card-02.pdf` | OCR | 153 dias; competência lida do cabeçalho |
| `time-card-03.pdf` | OCR | 280 dias; até 4 batidas |
| `time-card-04.pdf` | OCR manuscrito | 5 páginas preservadas, sem dias confiáveis |
| `payroll-01.pdf` | texto | ficha financeira preservada como 5 páginas vazias; bônus não implementado |
| `payroll-02.pdf` | texto | 5 competências; tabelas MÊS e ACERTO |
| `payroll-03.pdf` | texto | 5 competências; verbas e bases separadas |
| `payroll-04.pdf` | OCR | 5 páginas; recibos duplicados deduplicados |

As planilhas foram geradas localmente em `entregas/`. Por privacidade, os PDFs recebidos e os XLSX resultantes não são versionados; os READMEs dessas pastas registram os resultados observados. Resultado vazio é deliberado nos dois layouts fora da confiança atual; a interface permite revisão manual.

## Configuração

Copie `.env.example` para `.env` se quiser mudar os padrões.

| Variável | Padrão | Efeito |
|---|---:|---|
| `PORT` | `8080` | Porta publicada pelo Compose |
| `MAX_UPLOAD_MB` | `15` | Limite do multipart e da cópia validada |
| `RETENTION_MINUTES` | `60` | Tempo sem atualização antes de apagar job e PDF |
| `WORKER_THREADS` | `2` | Processamentos simultâneos |
| `WORKER_QUEUE` | `20` | Fila máxima antes de responder 503 |
| `PDF_MIN_TEXT_CHARACTERS` | `120` | Mínimo de caracteres úteis para não chamar OCR |
| `OCR_DPI` | `300` | Resolução da página renderizada |
| `OCR_TIMEOUT_SECONDS` | `90` | Limite do Tesseract por página |
| `TESSERACT_LANGUAGE` | `por+eng` | Idiomas do OCR |
| `TRANSCRIPTION_STORAGE_DIR` | `/data` | Diretório temporário no container |

## Desenvolvimento e testes

O projeto usa Java 17, Spring Boot 3, PDFBox, Tesseract e Apache POI. O build Docker executa a suíte:

```bash
docker compose build quick-filler
```

São 19 testes cobrindo contrato/status, JSON literal, uploads inválidos, parsers observados, datas, meses, warnings e estilos/ordem do XLSX.

Para continuar com Codex, abra esta pasta como repositório: `AGENTS.md` é carregado automaticamente e encaminha para o contrato completo em `CODEX.md`.

Documentação complementar:

- [Solução e limitações](SOLUCAO.md)
- [Processo real de implementação](PROCESSO.md)
- [Enunciado oficial preservado](docs/desafio-oficial/README.md)
- [Instruções oficiais de avaliação](docs/desafio-oficial/INSTRUCOES.md)
