# Painel de chaves do Tinker (Google Planilhas)

As chaves do Gemini e do Groq ficam numa planilha **privada** sua. Um script dela vira um link (App da Web) que o Tinker lê ao abrir
e quando a cota de todas as chaves acaba. Assim você adiciona ou desliga uma chave de qualquer lugar e todas as instâncias
do Tinker pegam sozinhas. O Tinker também avisa no painel qual chave ficou sem cota (só os 6 últimos caracteres).

## Montar (uma vez)

1. Crie uma planilha nova no Google Planilhas (pode chamar de "Chaves do Tinker").
2. Menu **Extensões → Apps Script**.
3. Apague o conteúdo de `Code.gs` e cole o deste arquivo `Code.gs`.
4. Clique em **+** (Arquivos) → **HTML**, dê o nome `Painel` e cole o conteúdo de `Painel.html`.
5. Salve. Clique em **Implantar → Nova implantação** → engrenagem → **App da Web**:
   - Executar como: **Eu**
   - Quem pode acessar: **Qualquer pessoa**
   
   Toque em Implantar e autorize (o Google avisa que o app não é verificado: é o seu próprio script; "Avançado → Acessar").
6. Volte à planilha e recarregue a página. Aparece o menu **Tinker → Abrir painel** (abre uma janela no meio da tela).

## Usar

- No painel: cole a chave (o provedor é reconhecido sozinho: Groq começa com `gsk_`), toque em **+ Adicionar** (ela é testada na hora), **Desligar/Ligar** e **Apagar**.
- Copie o **Link para o Tinker** e cole no app: **Ajustes → Assistente (IA) → Painel de chaves → Colar**.
- Chave desligada ou apagada no painel sai do Tinker na próxima atualização. As chaves que você pôs à mão no app continuam.

## Aprendizado da IA

O Tinker também manda o que a IA aprendeu (lições e a biblioteca de cada jogo) para esta planilha, e a planilha junta
o que vem de todas as instâncias. A aba **Aprendizado** mostra tudo para ler; a aba escondida **MemoriaBruta** é a que o
Tinker usa. Uma instância nova (ou reinstalada) pega o aprendizado de lá ao abrir.

## Nada é apagado

A planilha é o banco de dados do app. O app e o script não apagam nada dela:
- todos os relatórios e buscas ficam guardados, o mais novo em cima;
- salvo ou receita apagado no app continua na planilha, com **Situação: apagado no app**, e o app não traz de volta;
- lições e notas só são somadas.

Apagar à mão funciona assim:
- **Relatórios, Buscas, Pedidos e Aparelhos:** apague as linhas que quiser, direto na aba.
- **Salvos e Receitas:** apague no app; ou, para tirar de vez da planilha, use **Tinker → Apagar de vez os salvos e receitas marcados como apagados**.
- **Aprendizado, Salvos, Receitas e Problemas** são abas de leitura refeitas pelo script. Linha apagada nelas volta na próxima atualização.
- Remover uma chave no painel é sempre você quem faz.

## Patches de código

A aba **Patches** mostra os patches de código de cada jogo: onde ficam (lib+deslocamento), os bytes originais e os novos, se estão ligados e se ficaram suspeitos. Um patch que chega de outro aparelho entra **desligado**.

## Versão do script

O `Code.gs` tem `const VERSAO` no começo. O número aparece no menu (**Tinker vN**) e embaixo do painel. Ele também aparece no registro do app: "Painel de chaves: … · script vN". Se o app avisar "desatualizado", cole o Code.gs novo e faça **Nova versão** na implantação.

## Pedidos e Aparelhos

Na aba **Pedidos** você escolhe o **Tipo** e escreve o **Texto**:
- `dica`: só um texto para você ler no celular (ex.: o que buscar e como); nada é executado;
- `nota`: anota no jogo (coluna **Jogo** com o pacote, ex.: `com.sega.sprint`, ou vazia para o jogo aberto);
- `licao`: guarda uma lição para a IA;
- `autoteste`: roda o autoteste;
- `relatorio`: manda o relatório.

O Tinker lê a aba a cada 3 minutos e mostra o pedido no painel. Ele **só acontece quando você toca em Permitir só desta vez** no celular, e **Ignorar** descarta o pedido. Para `autoteste` e `relatorio` também há **Sempre permitir**: dali em diante esses pedidos acontecem sozinhos (Situação: automático). Para voltar a perguntar, use Ajustes → Voltar a perguntar. `nota` e `licao` pedem o toque toda vez. A **Situação** e a **Resposta** voltam para a planilha. Outros tipos são recusados.

A aba **Aparelhos** mostra, para cada aparelho, a versão do Tinker, o último contato e o último autoteste.

## Relatórios

Quando o jogo fecha sozinho, ou quando você toca em **Compartilhar relatório**, o Tinker manda o relatório para a aba
**Relatórios** (os 200 últimos, o mais novo em cima). O **Link de leitura** do painel devolve só os relatórios e o
aprendizado, nunca as chaves: é o que você passa para quem for analisar os problemas.

## Consenso e problemas

- Salvo mandado por várias instâncias: cada caminho de ponteiro ganha um voto por aparelho (confirmado ao reabrir vale 3);
  o mais votado vira o ponteiro sugerido. A assinatura AOB se junta byte a byte (o que muda entre aparelhos vira ??).
- Aba **Problemas**: os erros dos relatórios agrupados, com quantas vezes, jogos, versões e quando. Refeita a cada
  relatório, com um **Diagnóstico (IA)**: o Gemini (com uma chave ativa da aba Chaves) escreve a causa provável e o
  que corrigir, dois problemas novos por vez. É só texto: nenhuma correção roda sozinha. Para ficar em dia de hora em hora use **Tinker → Ligar análise automática dos relatórios** (uma vez).

## Segurança

- A planilha continua privada. O link só devolve as chaves para quem tem o token que vai nele.
- Link vazou? **Tinker → Gerar novo token** na planilha e cole o link novo no app (o antigo para de funcionar).
- Use chaves do nível grátis, sem cobrança ativada no projeto do Google: no pior caso alguém gasta a cota, não dinheiro.
- Já tinha a versão anterior do painel? Cole os dois arquivos de novo e faça uma **Nova versão** da implantação (o link continua o mesmo).
- Mudou o código do script? **Implantar → Gerenciar implantações → editar → Nova versão**, senão o link continua com o código antigo.
