-- Assinatura automática para o GameGuardian (Tinker, 30/10/2026)
-- 1) Ache o valor e refine até sobrar 1 resultado.  2) Rode este script: "Marcar".
-- 3) Feche e abra o jogo, ache o valor de novo (1 resultado) e rode: "Comparar" (pode repetir: cada vez fica mais firme).
-- 4) Daqui pra frente, com o jogo aberto, rode: "Achar" — ele acha o endereço sozinho pela assinatura e põe na lista salva.
-- Só lê a memória para montar a assinatura; quem escreve valor é você, na lista do GG.

local JANELA = 64        -- bytes para cima e para baixo (64 = 16 números de cada lado)
local ARQ = gg.EXT_STORAGE .. "/tinker-assinatura.txt"

local function ler(alvo)  -- os Dwords em volta do alvo: { [deslocamento] = valor }
  local t = {}
  for d = -JANELA, JANELA, 4 do t[#t + 1] = { address = alvo + d, flags = gg.TYPE_DWORD } end
  local r, out = gg.getValues(t), {}
  for i, v in ipairs(r) do out[-JANELA + (i - 1) * 4] = v.value end
  return out
end

local function ponteiro(v)  -- números enormes costumam ser endereços: mudam a cada abertura, não servem
  return v > 0x10000000 or v < -0x10000000
end

local function gravar(nome, tipo, viz)
  local f = io.open(ARQ, "w")
  f:write(nome, "\n", tipo, "\n")
  for d, v in pairs(viz) do f:write(string.format("%d %d\n", d, v)) end
  f:close()
end

local function carregar()
  local f = io.open(ARQ, "r")
  if not f then return nil end
  local nome, tipo, viz = f:read("*l"), tonumber(f:read("*l")), {}
  for l in f:lines() do
    local d, v = l:match("^(-?%d+) (-?%d+)$")
    if d then viz[tonumber(d)] = tonumber(v) end
  end
  f:close()
  return nome, tipo, viz
end

local function umResultado()
  if gg.getResultsCount() ~= 1 then
    gg.alert("Refine até sobrar 1 resultado (agora tem " .. gg.getResultsCount() .. ").")
    return nil
  end
  return gg.getResults(1)[1]
end

local function marcar()
  local r = umResultado(); if not r then return end
  local nome = gg.prompt({ "Nome do valor (ex.: Barra de Cobre)" }, { "Meu valor" }, { "text" })
  if not nome then return end
  gravar(nome[1], r.flags, ler(r.address))
  gg.alert("Marcado: " .. nome[1] .. "\nAgora feche e abra o jogo, ache o valor de novo e rode \"Comparar\".")
end

local function comparar()
  local nome, tipo, viz = carregar()
  if not nome then gg.alert("Rode \"Marcar\" primeiro.") return end
  local r = umResultado(); if not r then return end
  local agora, fica, n = ler(r.address), {}, 0
  for d, v in pairs(viz) do
    if d ~= 0 and agora[d] == v and not ponteiro(v) then fica[d] = v; n = n + 1 end
  end
  if n < 3 then gg.alert("Só " .. n .. " vizinhos ficaram iguais: pouco para uma assinatura. Tente uma JANELA maior no script.") return end
  gravar(nome, tipo, fica)
  gg.alert("Assinatura de " .. nome .. ": " .. n .. " vizinhos fixos.\nJá dá para usar \"Achar\". Comparar de novo depois de outra abertura deixa ainda mais firme.")
end

local function achar()
  local nome, tipo, viz = carregar()
  if not nome then gg.alert("Rode \"Marcar\" e \"Comparar\" primeiro.") return end
  -- âncora: o vizinho fixo diferente de 0 (0 existe aos milhões); os outros conferem
  local ad, av
  for d, v in pairs(viz) do if v ~= 0 and (not ad or math.abs(v) > math.abs(av)) then ad, av = d, v end end
  if not ad then gg.alert("A assinatura só tem zeros: compare de novo com uma JANELA maior.") return end
  gg.clearResults()
  gg.searchNumber(tostring(av), gg.TYPE_DWORD)
  local total, achados = gg.getResultsCount(), {}
  local cands = gg.getResults(math.min(total, 20000))
  for _, c in ipairs(cands) do
    local alvo, conf = c.address - ad, {}
    for d, _ in pairs(viz) do conf[#conf + 1] = { address = alvo + d, flags = gg.TYPE_DWORD } end
    local ok = true
    for i, v in ipairs(gg.getValues(conf)) do
      if v.value ~= viz[v.address - alvo] then ok = false break end
    end
    if ok then achados[#achados + 1] = { address = alvo, flags = tipo, name = nome } end
  end
  gg.clearResults()
  if #achados == 0 then gg.alert("Não achei " .. nome .. " (" .. total .. " candidatos). A tela do valor precisa estar carregada; tente de novo.") return end
  gg.addListItems(achados)
  gg.loadResults(achados)
  gg.alert("Achei " .. #achados .. " endereço(s) de " .. nome .. ": estão na lista salva e nos resultados.")
end

local c = gg.choice({ "1. Marcar (com 1 resultado)", "2. Comparar (depois de reabrir o jogo)", "3. Achar pela assinatura" }, nil, "Assinatura automática")
if c == 1 then marcar() elseif c == 2 then comparar() elseif c == 3 then achar() end
