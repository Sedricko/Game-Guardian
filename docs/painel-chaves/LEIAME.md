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

## Segurança

- A planilha continua privada. O link só devolve as chaves para quem tem o token que vai nele.
- Link vazou? **Tinker → Gerar novo token** na planilha e cole o link novo no app (o antigo para de funcionar).
- Use chaves do nível grátis, sem cobrança ativada no projeto do Google: no pior caso alguém gasta a cota, não dinheiro.
- Já tinha a versão anterior do painel? Cole os dois arquivos de novo e faça uma **Nova versão** da implantação (o link continua o mesmo).
- Mudou o código do script? **Implantar → Gerenciar implantações → editar → Nova versão**, senão o link continua com o código antigo.
