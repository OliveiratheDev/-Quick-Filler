# Contrato de implementação — Quick Filler

Este arquivo governa toda a implementação deste repositório. Em caso de ambiguidade, vale a interpretação mais conservadora: não inventar dados, preservar o original e documentar o corte em `SOLUCAO.md`.

## Objetivo e prioridade

Construir uma aplicação web deployável para o ciclo completo de **cartão de ponto** e **holerite**:

`PDF upload -> processamento assíncrono -> revisão editável com PDF lado a lado -> download da planilha corrigida`

O pipeline de upload, job, revisão e download é único. Apenas o parser/extrator e o exportador específico variam. O prazo-alvo é cerca de 14 horas: fluxo completo e contrato HTTP têm precedência sobre profundidade de layouts, bônus ou arquitetura excessiva. Cortes devem manter ambos os tipos parcialmente suportados e ser registrados em `SOLUCAO.md`.

## Stack e operação

- Java 17, Spring Boot 3 e Apache PDFBox no backend.
- OCR pragmático por Tesseract em processo externo, configurável e instalado com português no container.
- Apache POI para XLSX.
- SPA simples servida pelo próprio Spring Boot.
- Persistência concorrente em memória e arquivos em diretório temporário, com retenção curta configurável.
- `docker compose up --build` deve subir a solução inteira.
- Nenhum banco, login, segredo ou bônus antes do core.

## Regras de domínio não negociáveis

1. Nunca inventar valor. Caractere incerto é `?` exatamente na posição incerta.
2. Nunca criar data impossível. Se não for possível validar, preservar o raw com `?`/incerteza; jamais normalizar para data falsa.
3. Raw e normalizado coexistem; o original nunca é descartado.
4. Valores monetários de holerite são sempre `String` brasileira, nunca `float`/`double`.
5. Avisos são derivados somente na exibição/exportação; jamais persistidos no JSON.
6. Dias e batidas preservam a ordem do documento.
7. `fields` contém somente verbas; bases e totais ficam somente em `bases`.
8. Logs não contêm nome, CPF, matrícula, salário, jornada ou OCR bruto.

## Contrato HTTP literal

- `POST /api/transcricoes`, `multipart/form-data` com `arquivo` (PDF) e `tipo` (`cartao-ponto` ou `holerite`), retorna **202 Accepted** e `{ "id": "<uuid>" }`.
- `GET /api/transcricoes/:id` retorna **200** com `id`, `tipo`, `status`, `erro`, `value`. Status só pode ser `processando`, `concluido` ou `erro`; durante processamento `value=null`; no erro, `erro` é legível.
- `PUT /api/transcricoes/:id` recebe `{ "value": { ... } }` e substitui integralmente a revisão.
- `GET /api/transcricoes/:id/planilha?formato=xlsx|csv|json` gera arquivo da versão corrigida pelo PUT.
- `GET /healthz` retorna **200 OK**.

### JSON de cartão de ponto

```json
{
  "pages": [
    {
      "page": 1,
      "days": [
        {
          "date_raw": "21/05/2019",
          "punches": [
            { "kind": "IN", "time_raw": "08:25", "time_hhmm": "08:25" },
            { "kind": "OUT", "time_raw": "18:25", "time_hhmm": "18:25" }
          ]
        },
        { "date_raw": "25/05/2019", "punches": [] }
      ]
    }
  ]
}
```

`page` começa em 1. `days` e `punches` nunca são reordenados. `kind` alterna `IN`/`OUT`. `time_raw` preserva a leitura; `time_hhmm` só normaliza quando deterministicamente seguro e preserva `?` na posição incerta.

### JSON de holerite

```json
{
  "pages": [
    {
      "page": 1,
      "year": "2020",
      "month": "01",
      "fields": [
        { "code": "0010", "label": "Salário Base", "reference": "220,00", "value": "2.389,77" }
      ],
      "bases": [
        { "label": "Base INSS", "value": "2.545,68" },
        { "label": "Total Vencimentos", "value": "2.545,68" },
        { "label": "Valor Líquido", "value": "2.282,81" }
      ]
    }
  ]
}
```

`year`/`month` são strings; mês legível somente `01`..`12`. `code` e `reference` são vazios quando ausentes. `label` não inclui o código. Base INSS, Base IR, FGTS, Total Vencimentos e Valor Líquido não entram em `fields`.

## Avisos derivados e cores

- Cartão: batidas ímpares (amarelo); data não sequencial entre linhas legíveis (vermelho).
- Holerite: página vazia (amarelo); mês não imediatamente seguinte à página legível anterior (vermelho); competências ilegíveis são ignoradas na cadeia; dezembro -> janeiro é consecutivo.
- Qualquer `?` na linha é amarelo.
- Amarelo: `#FFF3CD`.
- Vermelho: `#F8D7DA`, com borda esquerda `#DC3545` na primeira célula; vermelho vence amarelo.
- Header XLSX: branco, negrito, fundo `#173772`.

## Exportação

- Cartão XLSX: `Data, Entrada 1, Saída 1, Entrada 2, Saída 2, ...`; pares conforme maior quantidade de batidas; uma linha por `day`, na ordem original.
- Holerite XLSX: `Pág., Mês, Ano` e depois a união de labels de `fields` na ordem da primeira aparição; uma linha por página; valor ou vazio. Bases/totais não são colunas de verba.
- XLSX, CSV e JSON são obrigatórios e devem seguir o mesmo dado revisado.

## Processamento e extração

- Validar extensão, MIME, magic bytes `%PDF`, tamanho configurável e parser real.
- Upload cria UUID, retorna 202 imediatamente e processa fora da thread HTTP.
- Job store deve evitar condições de corrida básicas e capturar erro amigável.
- Para cada página: texto embutido primeiro; se insuficiente, renderizar e executar OCR.
- Aquisição de texto é separada da interpretação de layout.
- Há uma interface comum de extrator, implementada por cartão e holerite.
- Uma função central de validação/sanitização não transforma token duvidoso em melhor palpite.
- Os PDFs reais de `exemplos/` são a única referência aceita para ajuste específico de layout. Há quatro arquivos de cada tipo; qualquer alteração precisa preservar o comportamento dos oito e documentar limites observados.
- Localizar colunas e limites de tabela por cabeçalhos semânticos. Posição fixa só pode existir como fallback explícito e testado.

## Interface

- Upload de PDF com escolha de tipo.
- Estado de processamento com polling do GET.
- Revisão com PDF ao lado e tabela editável por tipo sobre base comum.
- Avisos com cores e motivo textual.
- Correções são salvas por PUT antes do download; download fica bloqueado enquanto há alterações não salvas.
- Download XLSX obrigatório.

## Segurança, privacidade e retenção

- `MAX_UPLOAD_MB` limita upload.
- PDF corrompido, gigante e concorrência têm resposta/comportamento definido.
- Arquivos e jobs expiram após retenção curta configurável; limpeza física deve ser implementada.
- IDs são UUIDs e nenhum segredo é versionado.

## Testes e entrega

Testar no mínimo: ciclo de status/contrato, dezembro->janeiro e competência ilegível intermediária, batidas ímpares, data inválida sem invenção, união/ordem de colunas de holerite, XLSX/cores, separação `fields`/`bases` e upload inválido/corrompido. Adicionar CI de build/testes.

Entregar `README.md`, `SOLUCAO.md`, `PROCESSO.md`, Dockerfile(s), `docker-compose.yml`, workflow de CI e planilhas de todos os PDFs encontrados em `exemplos/`. `PROCESSO.md` deve relatar ferramentas e fatos reais desta execução, incluindo erros, reescritas, decisões alternativas, primeiro ponto de quebra em produção e áreas de baixa confiança.

## Ordem de trabalho

1. Contrato/API/modelos/job store/healthz.
2. PDF + fallback OCR.
3. Parsers mínimos de ambos os tipos guiados por `exemplos/`.
4. Warnings e exports.
5. Frontend completo.
6. Docker/Compose.
7. Testes.
8. Processar todos os exemplos e ajustar só com evidência.
9. Documentação e CI.
10. Deploy apenas depois do Docker local funcionar.

Antes de cada mudança grande, executar os testes/build relevantes. Ao final, validar `docker compose up --build`, `/healthz`, upload, polling, revisão, PUT e download para ambos os tipos.
