# PDFs usados na implementação

Estes oito arquivos vieram do pacote fornecido junto ao pedido de implementação (`drive-download-20260811T234453Z-1-001.zip`, SHA-256 `9824A92B02F3217D2F8ACFE7C26D50A1905075B1518EC929393FE873DB3A6C58`). Os PDFs permanecem apenas no ambiente local e são ignorados pelo Git para evitar publicar dados trabalhistas pessoais.

## Cartões de ponto

- `time-card-01.pdf`: texto embutido; linhas por dia com jornada prevista e batidas.
- `time-card-02.pdf`: scan tabular; OCR; dia na linha e competência no cabeçalho.
- `time-card-03.pdf`: scan; datas completas e horários marcados com sufixos de origem.
- `time-card-04.pdf`: scan de cartão manuscrito e baixo contraste.

## Holerites

- `payroll-01.pdf`: ficha financeira vertical com várias competências por página; expandida em uma entrada por competência.
- `payroll-02.pdf`: texto embutido; tabelas MÊS e ACERTO na mesma página.
- `payroll-03.pdf`: texto embutido; tabela tradicional de proventos/descontos e bases.
- `payroll-04.pdf`: scan; dois recibos duplicados por página e colunas paralelas.

Os documentos foram inspecionados visualmente, página por página. A cobertura e os cortes honestos estão em `SOLUCAO.md`; as planilhas geradas pela API ficam localmente em `entregas/`.
