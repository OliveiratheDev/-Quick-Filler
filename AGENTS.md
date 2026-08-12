# Instruções do projeto para o Codex

Antes de alterar código, leia integralmente `CODEX.md`. Ele é o contrato de implementação e contém as regras literais de domínio, HTTP, JSON e XLSX.

Leia também:

- `README.md` para execução e estado atual;
- `SOLUCAO.md` para decisões e limitações;
- `PROCESSO.md` antes de registrar fatos de uma nova rodada;
- `docs/desafio-oficial/README.md` e `docs/desafio-oficial/INSTRUCOES.md` quando a mudança tocar o enunciado;
- `exemplos/README.md` antes de ajustar parsers.

## Acordos de trabalho

- O pipeline é único para os dois tipos; não duplique upload, jobs, revisão ou download.
- Não altere nomes/campos do contrato HTTP ou JSON.
- Nunca adivinhe conteúdo de OCR. Preserve `?`, raw e ordem do documento.
- Dinheiro de holerite continua `String`; bases/totais nunca entram em `fields`.
- Warnings são derivados e nunca persistidos em `value`.
- Não registre nome de arquivo, texto extraído/OCR ou dados trabalhistas.
- Ajustes de layout devem ser sustentados pelos PDFs reais em `exemplos/` e por testes pequenos.
- Prefira localizar tabelas por cabeçalho; coordenada fixa é somente fallback documentado.
- `work/` é temporário e ignorado. Saídas dos exemplos pertencem a `entregas/`.

## Verificação mínima

Execute o build/testes em Docker, que é a unidade oficial da entrega:

```bash
docker compose build quick-filler
docker compose up -d
docker compose ps
```

Depois valide `/healthz` e, para ambos os tipos, POST 202, polling, PUT e downloads `xlsx`, `csv` e `json`. Ao mexer em extração, reprocesse todos os arquivos de `exemplos/` e registre cobertura/limitações reais em `SOLUCAO.md` e `PROCESSO.md`.
