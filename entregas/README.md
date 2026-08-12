# Planilhas geradas

Arquivos gerados pelo endpoint `GET /api/transcricoes/{id}/planilha?formato=xlsx` após processamento dos PDFs em `exemplos/` pela imagem Docker final.

Os arquivos XLSX ficam apenas no ambiente local e são ignorados pelo Git para evitar publicar dados trabalhistas pessoais. Esta tabela registra as saídas verificadas durante a implementação.

| Planilha | Linhas de dados | Observação |
|---|---:|---|
| `time-card-01.xlsx` | 153 | julho a novembro/2012 |
| `time-card-02.xlsx` | 153 | maio a setembro/2010, via OCR |
| `time-card-03.xlsx` | 280 | dezembro/2019 a setembro/2020, via OCR |
| `time-card-04.xlsx` | 0 | manuscrito sem leitura confiável |
| `payroll-01.xlsx` | 25 competências | abril/2017 a março/2019; 426 verbas e 226 bases em 5 páginas físicas |
| `payroll-02.xlsx` | 5 páginas | 34 colunas totais |
| `payroll-03.xlsx` | 5 páginas | 14 colunas totais |
| `payroll-04.xlsx` | 5 páginas | 17 colunas; uma competência ilegível e warning vermelho na página seguinte |

Todos os valores são células de texto para preservar formato brasileiro e zeros à esquerda. Os arquivos foram reabertos após o download: cabeçalho `#173772`, warnings e ausência de erros de fórmula foram verificados.
