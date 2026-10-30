-- Assinatura automática completa para o GameGuardian (Tinker, 30/10/2026)
-- Roda sempre o mesmo script: ele mesmo busca, pede para você mudar o valor no jogo, refina até sobrar 1, guarda os vizinhos
-- e, nas próximas aberturas do jogo, monta a assinatura e acha o endereço sozinho (com o nome, na lista salva).
-- Só lê a memória; quem escreve um valor novo é você, na lista do GG.

local JANELA = 64                                   -- bytes para cima e para baixo do valor
local INDICE = gg.EXT_STORAGE .. "/tinker-assinaturas.txt"   -- nomes dos valores (o GG não lista pastas)
local TIPOS = { { "Inteiro (Dword)", gg.TYPE_DWORD }, { "Com vírgula (Float)", gg.TYPE_FLOAT }, { "Grande com vírgula (Double)", gg.TYPE_DOUBLE } }

local function arq(nome) return gg.EXT_STORAGE .. "/tinker-assin-" .. nome:gsub("[^%w]", "_") .. ".txt" end

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
  local v = gg.prompt({ "Quanto " .. nome .. " mostra na tela agora?" }, { "" }, { "number" })
  if not v or v[1] == "" then return nil end
  gg.clearResults()
  gg.searchNumber(v[1], tipo)
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

local function entregar(nome, tipo, alvo, msg)
  local item = { { address = alvo, flags = tipo, name = nome } }
  gg.addListItems(item); gg.loadResults(item)
  gg.alert(msg .. "\n" .. nome .. " está na lista salva: edite ou congele por lá.")
end

-- escolher o valor
local lista = nomes()
local opcoes = { "➕ Novo valor" }
for _, n in ipairs(lista) do opcoes[#opcoes + 1] = n end
local c = gg.choice(opcoes, nil, "Assinatura automática")
if not c then return end
local nome = lista[c - 1]
local tipo, fase, viz
if not nome then
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
if fase >= 2 then
  local a = achar_por_assinatura(nome, tipo, viz)
  if #a == 1 then entregar(nome, tipo, a[1].address, "Achei pela assinatura ✅") return end
  gg.alert(#a == 0 and "A assinatura não achou (a tela do valor está aberta?). Vamos achar pelo valor e atualizar a assinatura."
                    or "A assinatura achou " .. #a .. " lugares. Vamos achar pelo valor e deixar ela mais firme.")
end

local alvo = achar_por_valor(nome, tipo)
if not alvo then return end
local agora = ler(alvo)
if fase == 0 then
  gravar(nome, tipo, 1, agora)
  entregar(nome, tipo, alvo, "Achei e guardei os vizinhos (1ª vez). Na próxima vez que abrir o jogo, rode de novo: eu comparo e monto a assinatura.")
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
entregar(nome, tipo, alvo, "Assinatura pronta com " .. n .. " vizinhos fixos ✅ Nas próximas vezes eu acho sozinho.")
