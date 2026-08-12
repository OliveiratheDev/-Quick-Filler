# Processo de implementação

## Ferramentas e agentes usados

- Codex implementou e revisou o projeto no workspace. Não foram usados subagentes.
- A conversa ChatGPT referenciada forneceu o briefing inicial; ela não continha os anexos completos.
- O repositório oficial `quick-filler/desafio-programador` foi clonado e lido integralmente, incluindo histórico visível, enunciado, instruções, licença e metadados.
- O ZIP fornecido depois foi inspecionado antes da extração; continha oito PDFs, com SHA-256 registrado em `exemplos/README.md`.
- Poppler renderizou todas as 40 páginas. Foram montadas folhas de contato e cada página foi revisada visualmente.
- `pypdf`/`pdfplumber` mediram páginas e camada textual. Isso separou documento digital, scan puro e camada acessória insuficiente.
- Tesseract `por+eng` no container gerou texto e TSV para amostras dos quatro layouts escaneados. O TSV foi usado só no diagnóstico de confiança; o produto ainda usa texto simples.
- Docker Compose compilou Java 17, executou Maven/JUnit e serviu a aplicação real. A API processou os oito PDFs.
- Apache POI gera e relê XLSX nos testes de ordem, cor e borda.

Artefatos de inspeção ficaram em `work/`, que é ignorado e não faz parte da entrega.

## Três caminhos errados e como foram percebidos

### 1. Assumir que não havia exemplos

Na primeira rodada, o workspace realmente não continha `exemplos/`; a solução foi concluída com fixtures sintéticos e a documentação declarou essa ausência. Depois, o usuário forneceu um ZIP fora do workspace. A inspeção revelou oito documentos reais e tornou a documentação anterior obsoleta. O código não foi tratado como pronto: todos os PDFs foram renderizados, processados e usados para reescrever os parsers.

### 2. Considerar 20 caracteres como texto suficiente

O primeiro critério de fallback OCR aceitava qualquer página com 20 caracteres úteis. `payroll-04.pdf` tem 84 caracteres por página na camada de assinatura, mas o conteúdo do recibo é imagem. A API devolveu cinco páginas vazias sem chamar OCR. A contagem por página e a comparação com a imagem mostraram o erro; o limiar foi elevado para 120, enquanto os digitais observados começam em 894.

### 3. Exigir data completa na linha do cartão

O parser inicial procurava `dd/mm/aaaa` em cada linha. Nos dois primeiros cartões, a linha contém apenas dia e o mês/ano aparece no cabeçalho. O sintoma foi objetivo: cinco páginas viraram somente cinco “dias”, capturados de cabeçalhos. A correção passou a localizar `Mês/Ano`, montar a data com validação, ignorar jornada prevista e unir linhas repetidas do mesmo dia. O resultado passou a 153 dias em cada arquivo.

## Partes reescritas diretamente e por quê

Não houve edição humana externa durante esta sessão. Os dois extratores foram reescritos pelo agente após a inspeção visual/textual, em vez de acumular exceções sobre o parser sintético.

- `TimecardExtractor`: ganhou competência de cabeçalho, dia isolado, merge consecutivo e limites semânticos das colunas.
- `PayslipExtractor`: ganhou máquina de estados por cabeçalho, colunas paralelas, valores negativos, deduplicação e expansão da ficha financeira por competência.
- `TokenSanitizer`: passou a preservar sinal monetário.
- O fallback OCR foi recalibrado com números medidos nos PDFs.
- README, `SOLUCAO.md` e este arquivo foram reescritos porque alegavam ausência de exemplos.

Depois da primeira entrega, a captura da interface mostrou que o corte da ficha financeira era visível como cinco páginas vazias. O layout real foi então inspecionado novamente e recebeu um parser separado, orientado pelos marcadores de competência e pelo período impresso, sem reutilizar o parser genérico de holerite.

## Três decisões com mais de uma resposta razoável

### 1. Parser por cabeçalho em texto linear, não por coordenadas

Coordenadas de palavras facilitariam as tabelas paralelas e evitariam alguns problemas de ordem. O próprio enunciado alerta que posição absoluta quebra entre layouts. Foi escolhida uma máquina de estados orientada a cabeçalhos, funcionando tanto com PDFBox quanto Tesseract. O custo é menor precisão quando a camada textual embaralha label e valor; tokens com bounding box são a evolução planejada.

### 2. Uma entrada por competência sem perder a página física

O enunciado do bônus manda compartilhar o mesmo `page` entre competências. O JSON passou a aceitar páginas repetidas em ordem não decrescente, e o extrator gera uma entrada por competência. Quando dezembro/2018 aparece em duas páginas físicas, as entradas não são unidas: preservar a origem física é mais auditável do que escolher uma página ou apagar essa distinção.

### 3. Primeira ocorrência no XLSX para labels duplicadas

`payroll-02` contém MÊS e ACERTO na mesma página, com labels repetidas. Somar valores, usar o último ou criar colunas duplicadas seriam respostas possíveis, nenhuma definida pelo contrato. O JSON preserva todos os registros; a matriz usa a primeira ocorrência, comportamento determinístico já existente. A limitação está explícita para revisão humana.

Outras decisões razoáveis foram monólito/SPA sem framework, Tesseract CLI sem serviço cloud e job store em memória para retenção de uma hora.

## O que quebra primeiro em produção

OCR é o primeiro gargalo. Dois workers renderizando páginas grandes a 300 DPI podem pressionar CPU e heap; um PDF pequeno com dimensões enormes contorna o limite em MB. Depois, a fila local chega a `WORKER_QUEUE` e responde 503.

Em múltiplas réplicas, o mapa em memória quebra afinidade: um GET pode chegar a uma réplica sem o job. Reinício também perde estados e pode deixar diretórios órfãos no volume.

## Onde não há confiança total

- `time-card-04`: manuscrito e baixo contraste; a saída automática fica vazia.
- `payroll-01`: o layout vertical observado está coberto, mas uma ficha financeira em matriz horizontal ou sem marcador `Mês` não usa essa estratégia. Competências iguais em páginas físicas diferentes ficam separadas.
- `payroll-04`: Tesseract separa números/totais, mas perdeu caracteres iniciais de alguns labels e uma competência. Não foram corrigidos por palpite.
- Um dígito OCR incorreto com sintaxe válida pode passar, porque confiança por caractere não chega ao domínio.
- Holerites com ordem textual muito diferente podem preservar a página, mas perder bases ou verbas.
- A decisão da primeira ocorrência no XLSX de páginas com MÊS/ACERTO exige confirmação de produto para produção.

## Estado verificável desta entrega

- 20 testes passaram no build Docker, sem falhas.
- O container ficou saudável com Tesseract `por+eng`.
- Os oito PDFs retornaram POST 202, passaram por polling e concluíram sem travar request HTTP.
- Os dois tipos tiveram PUT seguido de download validado em XLSX, CSV e JSON.
- `payroll-01` passou de cinco páginas vazias para 25 entradas não vazias, com 426 verbas e 226 bases; todos os valores de verba/base passaram pela validação de string monetária brasileira.
- A primeira versão do parser da ficha passou no teste unitário, mas o job real falhou porque o validador ainda proibia `page` repetido. Depois de liberar repetições apenas em ordem não decrescente, julho/2018 ainda perdeu linhas que continuavam na página física seguinte; a contagem por competência revelou isso e o estado passou a atravessar a quebra de página.
- As planilhas de `exemplos/` foram geradas pela API e salvas localmente em `entregas/`; PDFs e XLSX não foram publicados no Git por poderem conter dados trabalhistas pessoais.
- A automação do navegador integrado não iniciou por bloqueio `CreateProcessAsUserW` do sandbox Windows. Como fallback, o JavaScript da SPA passou em verificação sintática, `/` respondeu 200 e os elementos de polling, PDF, salvar e download foram conferidos no código; não alego um teste visual automatizado do navegador nesta máquina.
- A porta padrão 8080 estava ocupada por outro serviço na máquina de validação; o Compose foi testado em 18080 via arquivo temporário em `work/`, sem interromper processos alheios.
- Foi preparado um Blueprint gratuito do Render com Docker, healthcheck e limites conservadores de memória/OCR. A criação do serviço exige autorização explícita da conta Render do responsável pelo repositório.
