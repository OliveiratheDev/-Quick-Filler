# Solução técnica

## Arquitetura e fluxo

A entrega é um monólito Spring Boot com SPA estática no mesmo processo. Isso mantém um único Compose e, principalmente, um único ciclo para os dois documentos:

1. O controller valida `tipo`, extensão e MIME.
2. O serviço copia o upload com limite contado, verifica `%PDF-` e abre o arquivo com PDFBox.
3. Um UUID identifica o job em `ConcurrentHashMap`; o POST retorna 202.
4. Um executor com threads e fila limitadas processa fora da thread HTTP.
5. `PdfPageTextSource` lê texto por página. Texto insuficiente aciona renderização a 300 DPI e Tesseract.
6. `ExtractionCoordinator` seleciona um `TranscriptionExtractor` por tipo. Aquisição, job, storage e erros continuam compartilhados.
7. A SPA faz polling, mostra o blob local do PDF e oferece a grade específica do tipo.
8. O PUT valida e substitui integralmente a revisão.
9. O exportador deriva warnings e gera XLSX, CSV ou JSON da versão corrente.

Pacotes principais:

- `api`: contrato HTTP e erros públicos;
- `application`: ciclo do job e validação do JSON;
- `domain`: modelos literais;
- `extraction`: PDF/OCR comum, sanitização e parsers;
- `warnings`: regras derivadas;
- `export`: transposição, CSV/JSON e estilos XLSX;
- `infrastructure.storage`: job store concorrente.

## Stack e justificativa

- Java 17 + Spring Boot 3.5: multipart, JSON e execução assíncrona com pouca infraestrutura.
- PDFBox 3: valida, extrai texto e renderiza scans no mesmo runtime.
- Tesseract CLI `por+eng`: reproduzível em Docker e sem segredo externo.
- Apache POI: controle literal sobre strings, ordem e cores do XLSX.
- HTML/CSS/JavaScript sem framework: uma tela, sem segundo toolchain ou CORS.
- Memória + volume temporário: suficiente para retenção curta e uma réplica; banco foi cortado.

## Estratégia PDF/OCR

A decisão é página a página. O texto embutido só é aceito com pelo menos 120 letras/dígitos úteis. Esse valor veio dos arquivos reais: `payroll-04.pdf` possui apenas 84 caracteres de assinatura/camada acessória por página; o limiar inicial 20 impedia o OCR e devolvia páginas vazias. Os documentos digitais observados têm pelo menos 894 caracteres por página.

No fallback, PDFBox renderiza RGB a 300 DPI e chama Tesseract com `por+eng`. Imagem, texto e diagnóstico temporários são apagados ao fim da página. A exceção vira `status: erro` com mensagem curta.

`TokenSanitizer` aplica apenas validações determinísticas:

- data impossível mascara o componente impossível com `?`;
- horário seguro normaliza para `HH:MM`, preservando `time_raw`;
- horário incerto mantém `?`;
- dinheiro, inclusive negativo, permanece string brasileira.

Limite: o modo texto do Tesseract não entrega confiança por caractere. A solução não converte `O` em `0` nem corrige dígitos probabilisticamente; ainda assim, um dígito errado que o OCR devolva com aparência válida pode passar. TSV/hOCR com alinhamento de glifos é o próximo passo.

## Parsers guiados pelos exemplos

### Cartão de ponto

O parser identifica a estrutura pelo cabeçalho, não por coordenada absoluta. Ele suporta:

- data completa no início da linha;
- dia isolado quando mês/ano estão em `Mês/Ano` no cabeçalho;
- jornada prevista antes das batidas, descartada quando o cabeçalho declara `Jornada Entrada Saída`;
- linhas repetidas consecutivas do mesmo dia, unidas sem reordenar;
- horários com `:`, `h` ou `.`, inclusive sufixos do relatório;
- corte antes de ocorrência/colunas administrativas, para não exportar quantidade de hora extra como batida.

Resultados: `time-card-01` e `02` geram 153 dias cada; `time-card-03`, 280 dias. `time-card-04` é manuscrito, de baixo contraste, e o Tesseract não recuperou datas/horários com confiança. As cinco páginas são preservadas vazias para revisão, sem inventar dados.

### Holerite

O parser usa uma máquina de estados delimitada por cabeçalhos de tabela. Somente linhas entre cabeçalho e total viram `fields`. Bases/totais são reconhecidos separadamente, inclusive quando labels e valores aparecem em ordens distintas na camada textual.

Há suporte observado para:

- tabela `Verba / Nome / Base / Valor`;
- tabela `Código / Descrição / Unidade / Proventos / Descontos`;
- colunas paralelas de proventos/descontos em scan;
- múltiplas tabelas MÊS/ACERTO na mesma página;
- recibo duplicado na mesma página, deduplicado por registro exato;
- ficha financeira vertical, expandida para uma entrada por competência com o mesmo `page` físico;
- competência numérica ou nome do mês;
- valores negativos e referências textuais.

`payroll-01` é uma ficha financeira vertical. O extrator localiza o período completo e cada marcador `Mês`, resolve o ano de dois dígitos somente quando há uma única correspondência no período e mantém o número da página física. Seções repetidas da mesma competência e página, como folha normal e PLR, são unidas; uma seção que continua na página seguinte também é preservada. O PDF real gerou 25 entradas, 426 verbas e 226 bases. `payroll-02`, `03` e `04` continuam parcialmente suportados. No scan `payroll-04`, uma competência ficou ilegível e alguns labels perderam caracteres; esses são limites reais do OCR atual.

## Warnings e exportação

Warnings são objetos transitórios de `WarningService`; nunca entram em `value`.

- Datas legíveis são comparadas na ordem global; linha ilegível não redefine a anterior.
- Competências ilegíveis são ignoradas na cadeia; dezembro → janeiro é consecutivo.
- Batida ímpar/página vazia/`?` usam amarelo.
- Data ou mês não sequencial usa vermelho e vence amarelo.
- XLSX usa `#173772`, `#FFF3CD`, `#F8D7DA` e borda `#DC3545` conforme o contrato.

O XLSX de holerite usa somente a união de `fields`; `bases` ficam no JSON e na revisão, mas não contaminam colunas de verba. Quando uma página contém a mesma label em MÊS e ACERTO, a planilha usa a primeira ocorrência, porque somar ou escolher a última inventaria uma regra não especificada.

## Corrupção, tamanho e concorrência

- extensão/MIME inválidos: 415;
- magic bytes inválidos: 415;
- PDF corrompido, protegido ou sem páginas: 400;
- acima de `MAX_UPLOAD_MB`: 413;
- fila saturada: 503 e remoção do arquivo recém-copiado;
- download antes da conclusão: 409;
- PUT e transições de estado sincronizados por job.

Não há limite de páginas ou pixels renderizados além do limite em bytes. Um PDF pequeno com páginas enormes ainda pode pressionar memória; isso deve ser endurecido antes de produção.

## Retenção e privacidade

O PDF fica em diretório UUID sob `TRANSCRIPTION_STORAGE_DIR`. Job e diretório expiram após `RETENTION_MINUTES` desde a última atualização, 60 minutos por padrão.

O código não registra nome de arquivo, texto PDF/OCR nem conteúdo transcrito. Falhas registram apenas UUID e classe. A UI usa o blob que já está no navegador; não há endpoint extra para o original.

Reiniciar o processo perde o mapa e pode deixar diretórios órfãos no volume. Produção exigiria varredura de startup e storage compartilhado.

## Docker e variáveis

```bash
docker compose up --build
```

A imagem final usa JRE 17, Tesseract português/inglês, usuário sem privilégios, volume `/data` e healthcheck. Variáveis documentadas em `.env.example`:

- `PORT`, `MAX_UPLOAD_MB`, `RETENTION_MINUTES`;
- `WORKER_THREADS`, `WORKER_QUEUE`;
- `PDF_MIN_TEXT_CHARACTERS`, `OCR_DPI`, `OCR_TIMEOUT_SECONDS`;
- `TESSERACT_LANGUAGE`, `TRANSCRIPTION_STORAGE_DIR`.

## Deploy

O repositório inclui `render.yaml` para um Web Service Docker gratuito no Render. A configuração usa healthcheck em `/healthz`, uma única thread de OCR, fila curta, DPI 220 e limite explícito da JVM para conviver com a memória reduzida da instância de demonstração. O filesystem gratuito é efêmero: jobs e PDFs podem desaparecer em reinícios, além da limpeza normal por retenção.

## Testes escolhidos

São 20 testes. Eles protegem: ciclo/status e contrato JSON, upload inválido, substituição via PUT, datas impossíveis, dia isolado + competência, merge de batidas, exclusão de horários administrativos, verbas/bases, colunas paralelas, valor negativo, expansão de ficha financeira, continuação entre páginas e páginas físicas repetidas, sequência dezembro/janeiro, competência ilegível intermediária, batidas ímpares, união/ordem de labels e cores/borda do XLSX.

Esses casos foram escolhidos porque falhas neles geram dado errado com aparência válida ou quebram a avaliação automática.

## Limitações e cortes reais

- Sem deploy público: não houve ambiente/credencial de hospedagem.
- A ficha financeira é suportada para o layout vertical observado. Duas folhas da mesma competência em páginas físicas diferentes permanecem como entradas distintas, pois uni-las apagaria a origem `page` exigida pelo contrato.
- Cartão manuscrito (`time-card-04`) não é transcrito com segurança.
- Confiança OCR por caractere não é transportada.
- Alguns labels OCR válidos lexicalmente podem ter perdido a primeira letra; não foram “corrigidos” por dicionário.
- Bases de layouts desconhecidos ainda podem ficar incompletas, embora nunca sejam promovidas a verba.
- Job store não sobrevive a restart ou múltiplas réplicas.
- O PDF não reabre após refresh da SPA porque o viewer usa o blob local.
- Sem coordenadas/rastreabilidade visual ou detecção automática de tipo.

## Caminho para novos layouts

A aquisição da página não muda. Um novo layout deve ganhar uma estratégia selecionada por sinais determinísticos do cabeçalho, com fixture de texto/OCR e JSON esperado. Para melhorar scans, a aquisição pode passar a retornar tokens com bounding box e confiança; parsers continuam consumindo uma representação comum. Um terceiro tipo adicionaria modelo, extrator e export/UI específicos, reutilizando lifecycle, storage e page source.
