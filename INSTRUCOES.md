# Instruções para o Candidato

Leia o [`README.md`](README.md) primeiro — ele define o que construir. Este arquivo trata de **como** trabalhar e **como avaliamos**.

## Começando

1. Faça um fork deste repositório
2. Os PDFs de exemplo estão em `exemplos/`
3. Construa sua solução na linguagem que preferir
4. Publique a aplicação e envie o link do repositório + a URL

Organize o projeto como fizer sentido para você. Não temos estrutura de pastas preferida — temos o contrato de API e os formatos de saída, ambos no `README.md`.

## Orçamento de tempo

**~14 horas.** Uma sugestão de divisão, só para calibrar:

| Etapa | Tempo |
|---|---|
| Extração do cartão de ponto | ~3h |
| Extração do holerite | ~3h |
| OCR nos documentos escaneados | ~2h |
| API + processamento assíncrono | ~1h30 |
| Interface de revisão (compartilhada) | ~2h30 |
| Docker + deploy | ~1h |
| `SOLUCAO.md` + `PROCESSO.md` | ~30min |

Repare que interface, API, Docker e deploy aparecem **uma vez**. Se você está construindo duas de cada, o custo dobrou sem necessidade.

Se a extração consumir tudo, **pare e entregue o resto**. Uma aplicação completa que lê 70% dos dois documentos vale mais que um extrator perfeito sem interface, sem deploy e sem documentação. A recíproca também vale: uma casca bonita sem extração confiável não passa.

## Pesos da avaliação

| Critério | Peso | O que olhamos |
|---|---|---|
| **Precisão da extração** | 30% | Quanto da transcrição está correto, nos dois tipos de documento — que pesam igual |
| **Honestidade dos dados** | 15% | Os `?` estão onde deveriam estar? Chuta caractere? Marca de incerto o que leu bem? |
| **O ciclo completo funciona** | 20% | Enviar → acompanhar → corrigir → baixar, nos dois tipos. A correção chega na planilha? |
| **Arquitetura e operação** | 15% | `docker compose up` funciona? O pipeline é compartilhado entre os tipos? A app sobrevive a um documento demorado? |
| **Segurança e privacidade** | 10% | Validação de upload, limites, retenção, PII em log |
| **Código e decisões** | 10% | `SOLUCAO.md`, `PROCESSO.md`, legibilidade, testes onde importam |

Repare no que **não** está na tabela: quantidade de código, número de testes, tamanho do README. Nada disso soma pontos sozinho.

### Sobre "Honestidade dos dados"

É o critério que mais gente subestima, então vale ser explícito.

Um caractere marcado como `?` custa bem menos que um caractere errado com cara de certo. O primeiro é visto e corrigido na revisão; o segundo passa e chega ao cliente.

Encher a saída de `?` para se proteger também não funciona: se você diz que não leu nada, você não transcreveu nada.

O que queremos ver é **calibração** — você sabe onde sua solução é forte e onde é frágil, e a saída reflete isso.

## Testes

Escreva os testes que te deram confiança para entregar, e só esses. Em `SOLUCAO.md`, diga em uma linha **por que escolheu esses casos**.

Cobertura alta não impressiona ninguém em 2026 — gerar 200 testes é barato. Escolher os 8 que pegam os erros que importam, não.

## Erros comuns

Coisas que já vimos derrubar entregas boas:

- **Duas aplicações em vez de uma.** Cartão de ponto e holerite compartilham envio, fila, revisão, edição e download. O que muda é a leitura e a forma da planilha. Duplicar o resto custa metade do seu tempo e aparece na nota de arquitetura.
- **Processar dentro do request HTTP.** Funciona local e quebra em produção, quando o proxy da plataforma corta a conexão antes de a extração terminar. Pense em como o cliente descobre que o trabalho acabou.
- **Confundir `fields` com `bases` no holerite.** `Base INSS`, `Total Descontos` e `Valor Líquido` não são verbas. Se entrarem em `fields`, viram colunas na planilha e contaminam tudo.
- **Converter dinheiro para float.** `"2.389,77"` é string, e permanece string. Virar `2389.77` perde o formato original e abre a porta para erro de arredondamento.
- **Ordenar as linhas.** A saída segue a ordem do documento, página por página, de cima para baixo. Ordenar por data ou por competência esconde exatamente o sinal que a marcação de "não sequencial" existe para revelar.
- **Coordenadas fixas.** Amarrar a leitura a posições x/y absolutas quebra na primeira variação de layout — inclusive entre páginas do mesmo documento. Prefira localizar as colunas a partir do cabeçalho, e use posição fixa só como fallback.
- **Perder linhas em silêncio.** Se o cartão de ponto vai de 01 a 31 e sua saída tem 27 dias, 4 sumiram — quase sempre porque um filtro os descartou, não porque o documento não os tinha. Dias sem batida (`punches: []`) são linhas válidas. Vale o mesmo para páginas de holerite sem dados: a página continua na saída, marcada como vazia.
- **Descartar o valor original.** `date_raw` e `time_raw` guardam o que estava impresso. Se você só devolve o normalizado, ninguém consegue auditar de onde veio o erro.
- **Ajustar o código ao PDF de exemplo.** Uma data, um nome de coluna, uma posição fixa na página ou um número de páginas gravado no código resolve o exemplo e quebra em qualquer outro documento. Isso aparece na leitura do código.
- **Assumir que todo PDF tem camada de texto.** Parte dos exemplos é imagem escaneada, e `pdf-parse` e equivalentes devolvem vazio nesses casos. Detecte e caia para OCR em vez de devolver uma transcrição em branco.
- **Implementar só o `xlsx` no download.** O contrato pede `xlsx`, `csv` e `json`, e os três precisam funcionar.
- **Aceitar qualquer coisa no upload.** Um `.txt` renomeado para `.pdf` não pode virar transcrição. Recusar com `4xx` é o ideal; aceitar e terminar com `status: "erro"` também vale. Transcrever lixo como se fosse documento, não.
- **Deploy que não sobe.** Teste a URL numa janela anônima antes de mandar.

## Perguntas frequentes

**Posso usar bibliotecas de terceiros?**
Sim, quaisquer bibliotecas públicas.

**Posso usar Claude Code, Cursor, Copilot, ChatGPT?**
Sim, e queremos saber como. Veja a seção sobre uso de IA no `README.md`.

**Preciso implementar OCR?**
Sim. Parte dos documentos de exemplo é escaneada, sem camada de texto — extrair só o texto embutido não dá conta deles. Você escolhe a ferramenta (Tesseract, um serviço de nuvem, o que preferir) e documenta a escolha em `SOLUCAO.md`.

**Preciso detectar o tipo do documento automaticamente?**
Não. O tipo vem no campo `tipo` do upload. Detectar sozinho é bônus.

**Os dois tipos precisam da mesma qualidade de leitura?**
Idealmente sim, e eles pesam igual na nota de precisão. Se um ficou melhor que o outro, diga isso em `SOLUCAO.md` — reconhecer a assimetria conta a favor.

**Preciso guardar as transcrições em banco?**
Só se você quiser. Precisa funcionar entre o envio e o download, e a política de retenção precisa estar escrita — o resto é decisão sua.

**Posso mudar o formato do JSON?**
Não. São os formatos que rodam em produção aqui, e é isso que permite comparar todo mundo pelo mesmo critério. Se achar que algum tem defeito, siga-o mesmo assim e escreva em `SOLUCAO.md` o que mudaria — essa resposta conta a favor.

**Como sei quantas colunas a planilha tem?**
Cartão de ponto: pelo dia com mais batidas. Holerite: pela união de todos os `label` de `fields`, na ordem de primeira aparição. Nos dois casos, células sem valor ficam vazias.

**E se um registro do documento não fizer sentido?**
Aí você tem uma decisão a tomar. Tome, documente em `SOLUCAO.md`, e faça a interface mostrar o problema em vez de escondê-lo. Não existe uma única resposta certa — existe resposta justificada.

**Onde eu publico?**
Onde quiser, contanto que a URL abra. Free tier serve. Se a aplicação dorme por inatividade, sem problema.

**O que acontece depois?**
Quem avançar faz uma sessão de ~40 minutos, ao vivo, estendendo a própria solução para um layout novo, com agente liberado. Por isso vale entender de verdade o que você entregou.

---

**Sucesso no desafio! 🎯**
