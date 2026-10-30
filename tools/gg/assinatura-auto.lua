-- Biblioteca de valores para o GameGuardian (Tinker, 30/10/2026): painel para mudar e congelar, assinaturas por jogo
-- Painel: acha todos os valores deste jogo pela assinatura e deixa mudar ou congelar cada um.
-- Roda sempre o mesmo script: ele mesmo busca, pede para você mudar o valor no jogo, refina até sobrar 1, guarda os vizinhos
-- e, nas próximas aberturas do jogo, monta a assinatura e acha o endereço sozinho (com o nome, na lista salva).
-- Só lê a memória; quem escreve um valor novo é você, na lista do GG.

local JANELA = 64                                   -- bytes para cima e para baixo do valor
local JOGO = (gg.getTargetPackage and gg.getTargetPackage() or "jogo"):gsub("[^%w%.]", "_")
local INDICE = gg.EXT_STORAGE .. "/tinker-assinaturas-" .. JOGO .. ".txt"   -- nomes dos valores deste jogo
local NOMETIPO = { [gg.TYPE_DWORD] = "Dword", [gg.TYPE_FLOAT] = "Float", [gg.TYPE_DOUBLE] = "Double" }
local TIPOS = { { "Inteiro (Dword)", gg.TYPE_DWORD }, { "Com vírgula (Float)", gg.TYPE_FLOAT }, { "Grande com vírgula (Double)", gg.TYPE_DOUBLE } }

local function arq(nome) return gg.EXT_STORAGE .. "/tinker-assin-" .. JOGO .. "-" .. nome:gsub("[^%w]", "_") .. ".txt" end

local function ler(alvo)  -- Dwords em volta: { [deslocamento] = valor }
  local t = {}
  for d = -JANELA, JANELA, 4 do t[#t + 1] = { address = alvo + d, flags = gg.TYPE_DWORD } end
  local out = {}
  for i, v in ipairs(gg.getValues(t)) do out[-JANELA + (i - 1) * 4] = v.value end
  return out
end

local anotar  -- definida mais abaixo (gravar usa)

local function ponteiro(v) return v > 0x10000000 or v < -0x10000000 end

local function gravar(nome, tipo, fase, viz)
  local f = io.open(arq(nome), "w")
  f:write(nome, "\n", string.format("%d\n%d\n", tipo, fase))
  for d, v in pairs(viz) do f:write(string.format("%d %d\n", d, v)) end
  f:close()
  anotar(nome)
end

local function carregar(nome)
  local f = io.open(arq(nome), "r")
  if not f then return nil end
  local n, tipo, fase, viz = f:read("*l"), tonumber(f:read("*l")), tonumber(f:read("*l")), {}
  for l in f:lines() do
    local d, v = l:match("^(-?%d+) (-?%d+)$")
    if d then viz[tonumber(d)] = tonumber(v) end
  end
  f:close()
  return tipo, fase, viz
end

-- vários candidatos guardados (fase -1): cada um com os vizinhos; na próxima abertura do jogo o certo é o que se repete
local function gravarVarios(nome, tipo, lista)
  local f = io.open(arq(nome), "w")
  f:write(nome, "\n", string.format("%d\n%d\n", tipo, -1))
  for _, viz in ipairs(lista) do
    f:write("#\n")
    for d, v in pairs(viz) do f:write(string.format("%d %d\n", d, v)) end
  end
  f:close()
  anotar(nome)
end

local function carregarVarios(nome)
  local f = io.open(arq(nome), "r")
  local out = {}
  f:read("*l"); f:read("*l"); f:read("*l")
  for l in f:lines() do
    if l == "#" then out[#out + 1] = {} else
      local d, v = l:match("^(-?%d+) (-?%d+)$")
      if d and out[#out] then out[#out][tonumber(d)] = tonumber(v) end
    end
  end
  f:close()
  return out
end

local function nomes()  -- valores que já têm assinatura
  local out, f = {}, io.open(INDICE, "r")
  if f then for l in f:lines() do if l ~= "" then out[#out + 1] = l end end f:close() end
  return out
end

anotar = function(nome)
  for _, n in ipairs(nomes()) do if n == nome then return end end
  local f = io.open(INDICE, "a"); f:write(nome, "\n"); f:close()
end

-- busca + refino guiado até sobrar 1: devolve o endereço
local function achar_por_valor(nome, tipo)
  -- você já buscou/refinou no GG: continua dos seus resultados (com 1, usa direto)
  local ja = gg.getResultsCount()
  local usar = ja > 0 and gg.choice({ "Usar os " .. ja .. " resultado(s) que já filtrei", "Fazer uma busca nova" }, nil, nome) == 1
  if not usar then
    local v = gg.prompt({ "Quanto " .. nome .. " mostra na tela agora?" }, { "" }, { "number" })
    if not v or v[1] == "" then return nil end
    gg.clearResults()
    gg.searchNumber(v[1], tipo)
  end
  while gg.getResultsCount() > 1 do
    local n = gg.getResultsCount()
    local novo = gg.prompt({ n .. " lugares. Faça " .. nome .. " MUDAR no jogo (gaste, ganhe, fabrique) e digite o valor novo:" }, { "" }, { "number" })
    if not novo or novo[1] == "" then return nil end
    gg.refineNumber(novo[1], tipo)
  end
  if gg.getResultsCount() == 0 then gg.alert("Sumiu tudo: o valor pode ser de outro tipo. Rode de novo e escolha outro tipo.") return nil end
  return gg.getResults(1)[1].address
end

local function achar_por_assinatura(nome, tipo, viz)
  local ad, av
  for d, v in pairs(viz) do if v ~= 0 and (not ad or math.abs(v) > math.abs(av)) then ad, av = d, v end end
  if not ad then return {} end
  gg.clearResults()
  gg.searchNumber(tostring(av), gg.TYPE_DWORD)
  local achados = {}
  for _, c in ipairs(gg.getResults(math.min(gg.getResultsCount(), 20000))) do
    local alvo, conf, ok = c.address - ad, {}, true
    for d, _ in pairs(viz) do conf[#conf + 1] = { address = alvo + d, flags = gg.TYPE_DWORD } end
    for _, v in ipairs(gg.getValues(conf)) do if v.value ~= viz[v.address - alvo] then ok = false break end end
    if ok then achados[#achados + 1] = { address = alvo, flags = tipo, name = nome } end
  end
  gg.clearResults()
  return achados
end

local function esc(t)  -- texto dentro de JSON
  local r = tostring(t):gsub("\\", "\\\\"):gsub('"', '\\"')
  return r
end

-- receita do Tinker (a nossa biblioteca): a assinatura vira bytes (?? onde muda) e o valor fica em sigOff. Colar em
-- Tinker → Salvos → Colar lista; o Tinker sincroniza as receitas com a planilha (aba Receitas)
local function receita(nome, tipo, viz, valor)
  local hex, primeiro, ultimo = {}, nil, nil
  for d = -JANELA, JANELA, 4 do
    local v = viz[d]
    if v and d ~= 0 then
      local u, b = math.floor(v % 4294967296), {}  -- GG usa Lua 5.2 (sem //)
      for k = 1, 4 do b[k] = string.format("%02x", math.floor(u % 256)); u = math.floor(u / 256) end
      hex[#hex + 1] = table.concat(b)
      primeiro = primeiro or #hex; ultimo = #hex
    else hex[#hex + 1] = "????????" end
  end
  if not primeiro then return nil end
  local sig = table.concat(hex, "", primeiro, ultimo)
  local sigOff = JANELA - (primeiro - 1) * 4
  local t = tipo == gg.TYPE_FLOAT and "f32" or tipo == gg.TYPE_DOUBLE and "f64" or "i32"
  local pkg = gg.getTargetPackage and gg.getTargetPackage() or ""
  return string.format('{"tinker_receita":1,"jogo":"%s","receita":{"name":"%s","auto":false,"steps":[{"cls":"","off":0,"type":"%s","value":"%s","freeze":false,"label":"%s","sig":"%s","sigOff":%d}]}}',
    pkg, esc(nome), t, tostring(valor), esc(nome), sig, sigOff)
end

-- manda para a nossa base (aba Biblioteca da planilha): precisa do link do painel (o mesmo do Tinker, com ?token=),
-- pedido uma vez e guardado só neste celular
local LINK = gg.EXT_STORAGE .. "/tinker-painel.txt"
local function enviar(nome, tipo, viz, r)
  local f, link = io.open(LINK, "r"), nil
  if f then link = f:read("*l"); f:close() end
  if not link or link == "" then
    local p = gg.prompt({ "Para mandar à planilha: cole o link do painel (o mesmo do Tinker, com ?token=). Vazio = não mandar" }, { "" }, { "text" })
    link = p and p[1] or ""
    if link == "" then return "" end
    f = io.open(LINK, "w"); f:write(link); f:close()
  end
  local n = 0
  for _ in pairs(viz) do n = n + 1 end
  local sig, off = r:match('"sig":"([^"]*)","sigOff":(%d+)')
  local corpo = string.format('{"tinker":"biblioteca","jogo":"%s","nome":"%s","tipo":"%s","sig":"%s","sigOff":"%s","fixos":"%d","receita":"%s"}',
    JOGO, esc(nome), NOMETIPO[tipo] or tostring(tipo), sig or "", off or "", n, esc(r))
  local resp = gg.makeRequest(link .. (link:find("?", 1, true) and "&" or "?") .. "biblioteca=1", { ["Content-Type"] = "application/json" }, corpo)
  if type(resp) == "table" and resp.code and resp.code < 400 then return "\nEnviada para a planilha (aba Biblioteca) ✅" end
  return "\nNão consegui mandar para a planilha agora (" .. (type(resp) == "table" and tostring(resp.code) or tostring(resp)) .. "); ficou no celular."
end

-- receita copiada, guardada no celular e mandada para a planilha
local function publicar(nome, tipo, viz, alvo)
  local r = receita(nome, tipo, viz, gg.getValues({ { address = alvo, flags = tipo } })[1].value)
  if not r then return "" end
  gg.copyText(r, false)
  local f = io.open(gg.EXT_STORAGE .. "/tinker-biblioteca.txt", "a"); f:write(r, "\n"); f:close()
  return "\n\nReceita para o Tinker COPIADA: Salvos → Colar lista." .. enviar(nome, tipo, viz, r)
end

local function entregar(nome, tipo, alvo, msg)
  local item = { { address = alvo, flags = tipo, name = nome } }
  gg.addListItems(item); gg.loadResults(item)
  gg.alert(msg .. "\n" .. nome .. " está na lista salva: edite ou congele por lá.")
end

-- painel: acha cada valor pronto (assinatura firme) e deixa mudar, congelar ou soltar; volta ao painel até você sair
local function painel(lista)
  local itens = {}
  for _, n in ipairs(lista) do
    local tipo, fase, viz = carregar(n)
    if fase and fase >= 2 then
      local a = achar_por_assinatura(n, tipo, viz)
      itens[#itens + 1] = { nome = n, tipo = tipo, addr = #a == 1 and a[1].address or nil }
    end
  end
  if #itens == 0 then gg.alert("Ainda não há valor com assinatura pronta neste jogo. Refine um valor e guarde (3 testes).") return end
  local congelados = {}
  for _, l in ipairs(gg.getListItems()) do if l.freeze then congelados[l.address] = true end end
  while true do
    local nomesTela = {}
    for i, it in ipairs(itens) do
      if it.addr then
        local v = gg.getValues({ { address = it.addr, flags = it.tipo } })[1].value
        nomesTela[i] = it.nome .. " = " .. tostring(v) .. (congelados[it.addr] and "  ❄" or "")
      else nomesTela[i] = it.nome .. " (não achei agora: abra a tela dele)" end
    end
    local i = gg.choice(nomesTela, nil, "📋 Painel de " .. JOGO)
    if not i then return end
    local it = itens[i]
    if it.addr then
      local acao = gg.choice({ "✏️ Mudar valor", "❄ Congelar (no valor que eu digitar)", "🔓 Soltar (descongelar)" }, nil, it.nome)
      if acao == 1 or acao == 2 then
        local r = gg.prompt({ "Novo valor de " .. it.nome }, { "" }, { "number" })
        if r and r[1] ~= "" then
          local item = { address = it.addr, flags = it.tipo, value = r[1], name = it.nome, freeze = acao == 2 }
          gg.setValues({ item })
          if acao == 2 then gg.addListItems({ item }); congelados[it.addr] = true end
        end
      elseif acao == 3 then
        gg.addListItems({ { address = it.addr, flags = it.tipo, name = it.nome, freeze = false } })
        congelados[it.addr] = nil
      end
    end
  end
end

-- escolher o valor; com 1 resultado já refinado no GG, a 1ª opção é ele (o tipo vem do próprio resultado)
local lista = nomes()
local um = gg.getResultsCount() == 1 and gg.getResults(1)[1] or nil
local varios = gg.getResultsCount() >= 2 and gg.getResultsCount() <= 50 and gg.getResults(50) or nil
local opcoes, base = { "📋 Painel: mudar e congelar os valores deste jogo" }, 2
if um then opcoes[2] = "✔ Assinatura do resultado que filtrei (valor " .. tostring(um.value) .. ", " .. (NOMETIPO[um.flags] or "tipo " .. um.flags) .. ")"; base = 3 end
if varios then opcoes[2] = "💾 Guardar os " .. #varios .. " resultados (na próxima abertura eu descubro qual é o certo)"; base = 3 end
opcoes[base] = "➕ Novo valor"
for _, n in ipairs(lista) do opcoes[#opcoes + 1] = n end
local c = gg.choice(opcoes, nil, "Biblioteca de " .. JOGO)
if not c then return end
if c == 1 then painel(lista) return end
local nome = lista[c - base]
local tipo, fase, viz
local direto = um and c == 2
if varios and c == 2 then
  local r = gg.prompt({ "Nome deste valor (ex.: Barra de Cobre)" }, { "" }, { "text" })
  if not r or r[1] == "" then return end
  local lista = {}
  for _, v in ipairs(varios) do lista[#lista + 1] = ler(v.address) end
  gravarVarios(r[1], varios[1].flags, lista)
  gg.alert("Guardei os " .. #varios .. " candidatos de " .. r[1] .. ".\nFeche e abra o jogo, refine de novo (pode sobrar mais de 1) e rode o script escolhendo " .. r[1] .. ": o certo é o que tiver os mesmos vizinhos.")
  return
end
if direto then
  local r = gg.prompt({ "Nome deste valor (ex.: Barra de Cobre)" }, { "" }, { "text" })
  if not r or r[1] == "" then return end
  nome, tipo, fase = r[1], um.flags, 0
elseif not nome then
  local r = gg.prompt({ "Nome do valor (ex.: Barra de Cobre)" }, { "" }, { "text" })
  if not r or r[1] == "" then return end
  nome = r[1]
  local t = gg.choice({ TIPOS[1][1], TIPOS[2][1], TIPOS[3][1] }, nil, "Tipo do valor (o seu filtro mostrou F = Float)")
  if not t then return end
  tipo, fase = TIPOS[t][2], 0
else
  tipo, fase, viz = carregar(nome)
end

-- já tem assinatura firme: acha sozinho
if fase == -1 then
  local n = gg.getResultsCount()
  if n < 1 or n > 50 then gg.alert("Refine " .. nome .. " no GG até sobrarem até 50 resultados e rode de novo.") return end
  local antes, melhor, mn, malvo = carregarVarios(nome), nil, 0, nil
  for _, r in ipairs(gg.getResults(n)) do
    local agora = ler(r.address)
    for _, viz in ipairs(antes) do
      local fica, k = {}, 0
      for d, v in pairs(viz) do if d ~= 0 and agora[d] == v and not ponteiro(v) then fica[d] = v; k = k + 1 end end
      if k > mn then melhor, mn, malvo = fica, k, r.address end
    end
  end
  if mn < 3 then
    local lista = {}
    for _, r in ipairs(gg.getResults(n)) do lista[#lista + 1] = ler(r.address) end
    gravarVarios(nome, tipo, lista)
    gg.alert("Nenhum candidato repetiu os vizinhos ainda. Guardei os de agora: na próxima abertura do jogo, refine e rode de novo.")
    return
  end
  local a = achar_por_assinatura(nome, tipo, melhor)
  gravar(nome, tipo, 2, melhor)
  entregar(nome, tipo, malvo, "Descobri qual é o certo: o que repetiu " .. mn .. " vizinhos entre as aberturas ✅ (a assinatura acha " .. #a .. " lugar(es))." ..
    publicar(nome, tipo, melhor, malvo))
  return
end

if fase >= 2 then
  local a = achar_por_assinatura(nome, tipo, viz)
  if #a == 1 then entregar(nome, tipo, a[1].address, "Achei pela assinatura ✅") return end
  gg.alert(#a == 0 and "A assinatura não achou (a tela do valor está aberta?). Vamos achar pelo valor e atualizar a assinatura."
                    or "A assinatura achou " .. #a .. " lugares. Vamos achar pelo valor e deixar ela mais firme.")
end

local alvo = direto and um.address or achar_por_valor(nome, tipo)
if not alvo then return end
local agora = ler(alvo)
if fase == 0 then
  -- assinatura já na 1ª vez: os vizinhos que não parecem ponteiro; vale se agora ela acha só este lugar. Se um dia
  -- falhar (o jogo reabriu e algum vizinho mudou), o script volta a pedir o valor e deixa só os que ficaram iguais
  local rapida = {}
  for d, v in pairs(agora) do if d ~= 0 and not ponteiro(v) then rapida[d] = v end end
  local a = achar_por_assinatura(nome, tipo, rapida)
  if #a == 1 and a[1].address == alvo then
    gravar(nome, tipo, 2, rapida)
    entregar(nome, tipo, alvo, "Assinatura pronta ✅ (acha só este lugar). Da próxima vez é só rodar e escolher " .. nome .. "." ..
      publicar(nome, tipo, rapida, alvo))
    return
  end
  gravar(nome, tipo, 1, agora)
  entregar(nome, tipo, alvo, "Os vizinhos de agora acham " .. #a .. " lugares: guardei e, na próxima vez que abrir o jogo, rode de novo que eu comparo e monto a assinatura.")
  return
end
-- compara com o que estava guardado: fica só o que não mudou entre as aberturas do jogo
local fica, n = {}, 0
for d, v in pairs(viz) do
  if d ~= 0 and agora[d] == v and not ponteiro(v) then fica[d] = v; n = n + 1 end
end
if n < 3 then
  gravar(nome, tipo, 1, agora)
  entregar(nome, tipo, alvo, "Só " .. n .. " vizinhos ficaram iguais: recomecei com os de agora. Rode de novo na próxima abertura.")
  return
end
gravar(nome, tipo, 2, fica)
entregar(nome, tipo, alvo, "Assinatura pronta com " .. n .. " vizinhos fixos ✅ Nas próximas vezes eu acho sozinho." .. publicar(nome, tipo, fica, alvo))
