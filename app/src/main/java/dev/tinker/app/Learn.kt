package dev.tinker.app

import java.text.Normalizer

/**
 * Agente offline: o que deu certo e errado no 🎯 Achar sozinho pelo nome ("Moedas"), por jogo e por tipo, mais o que o
 * Claude já sabia da ferramenta (SEED, jogo "*"). Sem internet e sem tokens: a próxima busca do mesmo nome vai direto
 * no tipo que acertou, e um tipo que só errou neste jogo deixa de ser tentado primeiro. Vai para a planilha (aba Agente offline).
 */
data class Know(val game: String, val name: String, val type: String, val wins: Int = 0, val fails: Int = 0, val ms: Long = 0,
                val tips: String = "", val from: String = "aprendeu", val at: String = "")

/** "i32*10" = "Dword ×10" (guardado disfarçado: a tela mostra 23, a memória tem 230). */
internal fun typeLabel(t: String): String = t.substringBefore('*').removeSuffix("^").let { TYPE_NAMES[it] ?: it } +
    t.substringAfter('*', "").let { if (it.isEmpty()) "" else " ×$it" } + if (t.endsWith("^")) " (pela mudança: o número da tela é calculado)" else ""

/** "Moedas:" e "moedas" são o mesmo nome; acento e maiúscula não contam. */
internal fun learnKey(label: String): String =
    // "⏩ Velocidade:" é "velocidade": sem acento, maiúscula, emoji nem pontuação
    Normalizer.normalize(label.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex(" +"), " ").trim()

private fun seed(name: String, type: String, tips: String) = Know("*", name, type, tips = tips, from = "Claude")

// técnicas do Tinker por tipo de valor, na ordem em que costumam resolver (o nome é o começo da palavra na tela)
private const val MONEY = "1) Dword exato. 2) Não achou: faixa para frente (1.2K = 1150~1250) ou ↑↓ sem valor. 3) Unity: ligue 🔒 Procurar valores " +
    "cifrados. 4) 🧮 Disfarçado (dobro, ×10, +100, XOR). 5) Com 1: teste de 2 s antes de salvar; depois Ficha para ver o objeto."
private const val BAR = "1) Float (Unity) ou Dword (jogo nativo). 2) Barra sem número: Mais buscas → Começar sem valor e ↑ Aumentou/↓ Diminuiu. " +
    "3) O número da tela pode ser cópia: edite o que muda a tela no teste. 4) Congele ❄ em vez de escrever: o jogo recalcula."
private const val COUNT = "1) Dword exato, refine a cada mudança. 2) Anda sozinho (abates, tempo): use o nome ao lado e o 🎯 Achar sozinho. " +
    "3) Grupo 100;250::64 junta valores do mesmo objeto e corta milhares de resultados."
private const val ITEM = "1) Dword da quantidade no slot (cópia em +4; Floats da tela em +0x28/+0x2C, como no DYSMANTLE). 2) Cada slot é um " +
    "objeto: esvaziar muda de lugar, busque de novo. 3) Edite o Dword, não a cópia Float."

internal val SEED: List<Know> = listOf(
    seed("moeda", "i32", MONEY), seed("ouro", "i32", MONEY), seed("gold", "i32", MONEY), seed("coin", "i32", MONEY), seed("dinheiro", "i32", MONEY),
    seed("gema", "i32", MONEY), seed("gem", "i32", MONEY), seed("diamante", "i32", MONEY), seed("cristal", "i32", MONEY), seed("cash", "i32", MONEY),
    seed("vida", "f32", BAR), seed("hp", "f32", BAR), seed("saude", "f32", BAR), seed("health", "f32", BAR),
    seed("energia", "i32", BAR), seed("stamina", "f32", BAR), seed("mana", "f32", BAR),
    seed("nivel", "i32", COUNT), seed("level", "i32", COUNT), seed("xp", "i32", COUNT), seed("experiencia", "i32", COUNT),
    seed("abate", "i32", COUNT), seed("kill", "i32", COUNT), seed("ponto", "i32", COUNT), seed("score", "i32", COUNT),
    seed("municao", "i32", COUNT), seed("bala", "i32", COUNT), seed("ammo", "i32", COUNT),
    seed("madeira", "i32", ITEM), seed("pedra", "i32", ITEM), seed("material", "i32", ITEM), seed("recurso", "i32", ITEM), seed("poc", "i32", ITEM),
    seed("tempo", "f64", "1) ⏱ Cronômetros (Ferramentas) acha o fim da espera sozinho. 2) Tempo de jogo: Double ou Float que só sobe; ↑ Aumentou."),
    seed("velocidade", "f32", "1) ⏩ Velocidade (Ferramentas): acha e troca 1x/2x pelos valores do motor. 2) Nunca por gancho de tempo."),
    seed("preco", "i32", "1) 🛒 Loja (Ferramentas): lê os preços da tela e acha todos juntos."),
)

private fun how(situation: String, tips: String) = Know("*", "#$situation", "", tips = tips, from = "Claude")

// catálogo de técnicas (situação → modo de achar), da Ajuda do app; o "#" no nome tira estas do pickType (não são um nome da tela)
internal val TECHNIQUES: List<Know> = listOf(
    how("numero visivel", "Nova busca com o número exato; mude no jogo e Refinar até sobrar 1. Errou e sobrou 0: ↶ Desfazer."),
    how("numero arredondado", "Faixa: 1.2K vira 1150~1250; 100~200 para buscar e refinar."),
    how("muitos resultados", "Marque só o tipo certo (não Auto); grupo 100;250::64 com outro valor do mesmo objeto; ...A4 no refino guarda só os que terminam em A4; 📍 Onde o jogo escreve limita as regiões."),
    how("barra sem numero", "Mais buscas → Começar sem valor (barra costuma ser Float); ↑ Aumentou, ↓ Diminuiu, = Não mudou até sobrar pouco."),
    how("numero muda rapido", "🎯 Achar sozinho com o nome ao lado (ex.: Abates): lê a tela offline e refina a cada mudança."),
    how("busca normal nao acha", "1) 🔒 Procurar valores cifrados (Unity). 2) 🧮 Disfarçado: dobro, ×10, +100 ou cifrado com chave fixa. 3) Tipo Float ou Qword. 4) Cifrado manual 150X4."),
    how("valor volta sozinho", "Era cópia da tela ou o jogo recalcula: Ver ficha e edite o campo de verdade; ou ❄ Congelar."),
    how("recurso nao pode diminuir", "No salvo: O que escreve aqui? e gaste o recurso em 15 s; desligue a instrução (NOP) em Memória → Código."),
    how("jogo reabre e endereco muda", "Salvar já prepara ponteiros e assinatura; receitas guardam pela classe (Unity), ponteiro ou assinatura e voltam sozinhas."),
    how("unity", "Ficha com nomes (Player.hp), ⚙ Métodos (Sempre devolver), 🔓 Desbloquear, ⏩ Velocidade pela escala de tempo do motor."),
    how("sem unity", "Memória → Código: funções da lib pelo nome (gold, AddCoins), Contar chamadas, Sempre devolver; Busca por bytes (AOB)."),
    how("espera ou construcao", "⏱ Cronômetros: digite quanto falta (2h 30min) e Terminar agora."),
    how("loja", "🛒 Loja: 2 a 6 preços da tela (ou Ler da tela) e Tudo por 1."),
    how("recompensa repetir", "Foto do jogo: tirar foto, pegar a recompensa, ver o que mudou e voltar como estava."),
    how("save em arquivo", "💾 Editor de save: XML, PlayerPrefs ou JSON pelo nome; o Tinker faz backup e fecha o jogo antes de gravar."),
    how("jogo fechou", "Edição num endereço errado ou de outro tipo: refine até 1 e teste 2 s antes de salvar."),
    how("varios iguais", "Editar → Só os que valem agora: X → Y (todos os inimigos com 100 de vida)."),
    // tipos e formatos
    how("tipo certo", "Auto acha Dword, Float, Qword e Double de uma vez, mas traz mais lixo: com o tipo aprendido marque só ele. Dinheiro e itens: Dword; barras, posição e velocidade: Float; tempo e ids grandes: Qword/Double."),
    how("numero em dobro", "Flutter/JavaScript guardam inteiro em dobro (100 vira 200): o Tinker acha como Dword ×2 sozinho; no 🧮 Disfarçado escolha ×2."),
    how("lua", "Jogo com Lua (motor · Lua): 🌙 Lua acha só números do Lua; abra o resultado na Ficha para ver a tabela com o nome de cada campo."),
    how("unreal", "Unreal: na Ficha digite o nome do objeto ou da classe (Player, BP_Player_C) e Procurar, pelas tabelas do próprio motor."),
    how("cifrado com chave ao lado", "150X4 = valor cifrado com a chave 4 bytes depois (como no GameGuardian); ao editar um 🔒 o Tinker tranca o novo valor com a mesma chave."),
    // cortar resultados
    how("refino por final do endereco", "Depois de ver o endereço certo uma vez, ...A4 no refino deixa só os que terminam em A4 (o final costuma repetir entre aberturas)."),
    how("mesmo objeto", "Grupo: 100;250;7::128 acha os 3 valores a até 128 bytes um do outro; o resto (números soltos iguais) some de uma vez."),
    how("buscar perto", "Achou o ouro? Memória → Buscar perto procura a prata nas linhas em volta dele: muito mais rápido que buscar na memória toda."),
    how("so onde o jogo escreve", "📍 Onde o jogo escreve: Começar, carregue uma fase, Ver; usar nas buscas faz a busca começar só nessas regiões."),
    how("pausar na busca", "Ajustes → Pausar o jogo durante a busca: o valor não muda no meio da varredura (essencial em número que anda sozinho)."),
    // confirmar e não quebrar o jogo
    how("confirmar antes de salvar", "Com 1 a 3 resultados: IA pode testar valores (escreve +7 por 2 s e volta) ou Ver ficha: o campo verdadeiro fica no objeto com os vizinhos que fazem sentido."),
    how("copia temporaria", "Em Unity, Cópia temporária (Int32) é só o número da tela: editar não muda nada; salve o campo da classe (PlayerData.gold)."),
    how("congelar ou escrever", "Escrever muda uma vez (o jogo pode recalcular); ❄ Congelar reescreve várias vezes por segundo. Para vida/energia, congele; para moedas, escreva."),
    how("desfazer", "Refinou errado e sobrou 0: ↶ Desfazer volta à lista anterior; tocar de novo refaz."),
    // fazer durar
    how("receita ao abrir", "Marque os salvos e Criar receita com ao abrir: aplica sozinha quando o jogo carrega; guarda pela classe (Unity), ponteiro ou assinatura."),
    how("barra rapida", "⚡ Barra rápida sobre o jogo: valor, Buscar/Refinar e ↑↓=≠ sem abrir o painel; com 1 resultado, ❄ congela e salva; receitas viram ▶ botões."),
    how("distancia entre salvos", "Marque dois salvos para ver a distância: é o offset do campo no objeto, útil para achar o mesmo campo em outro objeto ou depois de atualizar."),
    how("exportar", "Salvos → Exportar leva para outro aparelho; Exportar .CT abre no Cheat Engine do PC."),
    // código
    how("contar chamadas", "Memória → Código ou ⚙ Métodos: Contar chamadas por 10 s enquanto faz a ação; a função que roda na hora certa é a do recurso."),
    how("sempre devolver", "Sempre devolver (número, sim/não ou Não fazer nada) vira um patch desligado: ligue e teste; volta sozinho ao reabrir o jogo."),
    how("quais rodam quando eu jogo", "Memória → Código → Quais rodam quando eu jogo? conta as funções que rodaram: compare parado com fazendo a ação."),
    how("aob em codigo", "Busca por bytes em Código acha a instrução de novo depois de atualizar (?? de curinga nos bytes que mudam)."),
    // roteiros completos
    how("roteiro moedas infinitas", "1) Achar o Dword (ou 🔒). 2) O que escreve aqui? e gastar. 3) Desligar a instrução que subtrai (NOP). 4) Se quebrar, Sempre devolver na função AddCoins/SpendCoins. 5) Receita ao abrir com o valor."),
    how("roteiro vida infinita", "1) Float pela barra (Sem valor ↓ ao levar dano). 2) Ver ficha: a vida máxima costuma estar ao lado. 3) Congelar ❄ no máximo. 4) Receita ao abrir."),
    how("roteiro desbloquear tudo", "Unity: 🔓 Desbloquear na tela da loja/inventário; senão, Ficha da classe do item: campos owned/isUnlocked viram 1 em todos com Editar."),
    how("roteiro depois de atualizar o jogo", "Ordem: assinatura AOB → classe+campo (Unity) → vizinho que voltou (distância) → função pelo nome (patch) → valor e tipo conhecidos → 🎯 Achar sozinho pelo nome."),
)

/** Tipos para tentar, na ordem, quando o primeiro do que se sabe já errou neste jogo. */
private val NEXT = mapOf("i32" to listOf("f32", "i64"), "f32" to listOf("i32", "f64"), "f64" to listOf("f32", "i64"), "i64" to listOf("i32"))

/** O que se sabe de um nome: o tipo para buscar primeiro (null = Auto) e a dica das técnicas. */
// o mesmo valor com nome em outro idioma (o jogo trocou de idioma, ou a versão em inglês): vale o que já se sabe do outro nome
private val SAME = listOf(
    setOf("ouro", "gold", "oro", "or"), setOf("moedas", "moeda", "coins", "coin", "monedas"), setOf("gemas", "gema", "gems", "gem"),
    setOf("diamantes", "diamante", "diamonds", "diamond"), setOf("cristais", "cristal", "crystals", "crystal", "cristales"),
    setOf("vida", "hp", "health", "life", "saude"), setOf("energia", "energy", "stamina"), setOf("madeira", "wood", "madera"),
    setOf("pedra", "stone", "piedra"), setOf("rubis", "rubi", "rubies", "ruby"), setOf("honra", "honor", "honour"),
    setOf("abates", "abate", "kills", "kill"), setOf("pontos", "ponto", "points", "score"), setOf("nivel", "level"),
    setOf("pocoes", "pocao", "potions", "potion"), setOf("sementes", "semente", "seeds", "seed"), setOf("municao", "ammo"),
    setOf("runas", "runa", "runes", "rune"), setOf("brilho", "shine", "glow"), setOf("vigor", "might"),
)
internal fun sameNames(k: String): Set<String> = SAME.firstOrNull { k in it } ?: setOf(k)

internal fun pickType(all: Collection<Know>, game: String, label: String): Pair<String?, String> {
    val k = learnKey(label)
    // "save" é onde o campo fica no arquivo do jogo (editor de save), não um tipo para buscar na memória
    // o nome exato primeiro; sem nada dele, o mesmo valor com o nome em outro idioma (Crystals = Cristais)
    val here = all.filter { it.game == game && it.name == k && it.type != "save" }
        .ifEmpty { all.filter { it.game == game && it.name in sameNames(k) && it.type != "save" } }
    val tips = all.firstOrNull { it.game == "*" && k.split(' ').any { w -> w.startsWith(it.name) } }?.tips.orEmpty()
    // um erro pesa o dobro de um acerto: um jeito que já enganou (cópia do número da tela) perde para o que foi provado
    here.filter { it.wins > 0 }.maxByOrNull { it.wins - 2 * it.fails }?.let { return it.type to tips }
    // errou aqui e nunca acertou: esse tipo sai da frente
    val wrong = here.filter { it.wins == 0 && it.fails > 0 }.map { it.type }.toSet()
    val others = all.filter { it.game != "*" && it.game != game && it.name == k && it.wins > 0 && it.type != "save" }.groupBy { it.type }
        .mapValues { e -> e.value.sumOf { it.wins } }.entries.sortedByDescending { it.value }.map { it.key }
    val known = all.firstOrNull { it.game == "*" && k.split(' ').any { w -> w.startsWith(it.name) } }?.type
    val order = (others + listOfNotNull(known) + listOfNotNull(known).flatMap { NEXT[it].orEmpty() }).distinct()
    return order.firstOrNull { it !in wrong } to tips
}

/** Guarda o resultado de uma busca pelo nome (acerto ou erro) no tipo que foi usado. */
internal fun record(all: List<Know>, game: String, label: String, type: String, ok: Boolean, ms: Long, at: String,
                    from: String = "aprendeu", tips: String = ""): List<Know> {
    val k = learnKey(label)
    // o que veio da IA só entra quando deu certo (o erro de uma sugestão dela não vira conhecimento)
    val old = all.firstOrNull { it.game == game && it.name == k && it.type == type } ?: if (from == "IA" && !ok) return all else Know(game, k, type, from = from, tips = tips)
    val wins = old.wins + if (ok) 1 else 0
    val new = old.copy(wins = wins, fails = old.fails + if (ok) 0 else 1, at = at,
        ms = if (ok) (old.ms * old.wins + ms) / wins else old.ms)
    return all.filterNot { it === old } + new
}

/** Junta o daqui com o de outra instância: o mesmo jogo+nome+tipo fica com o que tem mais tentativas. */
// shortcut: não soma (a planilha manda tudo de volta a cada vez e somaria em dobro); com vários aparelhos no mesmo jogo, vale o que testou mais
internal fun mergeKnow(a: List<Know>, b: List<Know>): List<Know> =
    (a + b).groupBy { Triple(it.game, it.name, it.type) }.values.map { g -> g.maxBy { it.wins + it.fails } }
