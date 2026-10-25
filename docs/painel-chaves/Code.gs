// Painel de chaves do Tinker: planilha privada com as chaves do Gemini e do Groq e um link (App da Web) que o Tinker lê.
// A planilha nunca fica pública: o link só devolve as chaves ativas para quem tem o token.

const ABA = 'Chaves';
const COLS = ['Chave', 'Nome', 'Ativa', 'Situação', 'Atualizado', 'Provedor'];

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Tinker')
    .addItem('Abrir painel', 'abrirPainel')
    .addItem('Gerar novo token (invalida o link antigo)', 'novoToken')
    .addToUi();
}

function abrirPainel() {
  const html = HtmlService.createHtmlOutputFromFile('Painel').setWidth(900).setHeight(640);
  SpreadsheetApp.getUi().showModalDialog(html, 'Chaves do Tinker');
}

function aba_() {
  const ss = SpreadsheetApp.getActive();
  let s = ss.getSheetByName(ABA);
  if (!s) {
    s = ss.insertSheet(ABA);
    s.setFrozenRows(1);
  }
  // planilha nova ou da versão anterior (5 colunas): cabeçalho com a coluna Provedor
  const head = s.getRange(1, 1, 1, COLS.length);
  if (head.getValues()[0].join('|') !== COLS.join('|')) head.setValues([COLS]).setFontWeight('bold');
  return s;
}

function token_() {
  const p = PropertiesService.getScriptProperties();
  let t = p.getProperty('TOKEN');
  if (!t) {
    t = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    p.setProperty('TOKEN', t);
  }
  return t;
}

// token só de leitura (relatórios e aprendizado, nunca as chaves): para quem for analisar os problemas
function leitura_() {
  const p = PropertiesService.getScriptProperties();
  let t = p.getProperty('LEITURA');
  if (!t) {
    t = 'l' + Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    p.setProperty('LEITURA', t);
  }
  return t;
}

function novoToken() {
  PropertiesService.getScriptProperties().deleteProperty('TOKEN');
  token_();
  SpreadsheetApp.getUi().alert('Token novo gerado. Abra o painel, copie o link de novo e cole no Tinker.');
}

function ativa_(v) {
  const t = String(v).toUpperCase();
  return v !== false && t !== 'FALSE' && t !== 'NÃO' && t !== 'NAO';
}

// coluna Provedor vazia: pelo jeito da chave (as do Groq começam com gsk_)
function provedor_(p, chave) {
  const t = String(p || '').trim().toLowerCase();
  if (t === 'groq' || t === 'gemini') return t;
  return chave.indexOf('gsk_') === 0 ? 'groq' : 'gemini';
}

function linhas_() {
  const s = aba_();
  const n = s.getLastRow() - 1;
  if (n < 1) return [];
  return s.getRange(2, 1, n, COLS.length).getValues()
    .map((r, i) => {
      const chave = String(r[0]).trim();
      return { linha: i + 2, chave: chave, nome: String(r[1]), ativa: ativa_(r[2]), situacao: String(r[3]), atualizado: r[4], provedor: provedor_(r[5], chave) };
    })
    .filter(r => r.chave);
}

function testar_(chave, provedor) {
  // lista de modelos: confere a chave sem gastar a cota de respostas
  const r = provedor === 'groq'
    ? UrlFetchApp.fetch('https://api.groq.com/openai/v1/models', { headers: { Authorization: 'Bearer ' + chave }, muteHttpExceptions: true })
    : UrlFetchApp.fetch('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1', { headers: { 'x-goog-api-key': chave }, muteHttpExceptions: true });
  const c = r.getResponseCode();
  return c === 200 ? 'ok' : c === 429 ? 'sem cota agora' : 'recusada (' + c + ')';
}

// ---- chamadas do painel ----

function dadosPainel() {
  const url = ScriptApp.getService().getUrl();
  const tz = Session.getScriptTimeZone();
  return {
    chaves: linhas_().map(r => ({
      linha: r.linha, fim: '…' + r.chave.slice(-6), nome: r.nome, ativa: r.ativa, situacao: r.situacao, provedor: r.provedor,
      atualizado: r.atualizado instanceof Date ? Utilities.formatDate(r.atualizado, tz, 'dd/MM HH:mm') : '',
    })),
    link: url ? url + '?token=' + token_() : '',
    leitura: url ? url + '?leitura=' + leitura_() : '',
  };
}

// várias de uma vez: uma chave por linha (ou separadas por espaço/vírgula), todas do provedor escolhido
function adicionar(texto, nome, provedor) {
  if (provedor !== 'gemini' && provedor !== 'groq') throw new Error('Escolha o provedor: Gemini ou Groq.');
  const lista = String(texto || '').split(/[\s,;]+/).map(c => c.trim()).filter(c => c);
  if (!lista.length) throw new Error('Cole pelo menos uma chave.');
  const tem = new Set(linhas_().map(r => r.chave));
  const s = aba_();
  let novas = 0, repetidas = 0, invalidas = 0, outro = 0;
  lista.forEach(chave => {
    if (!/^[A-Za-z0-9._-]{20,200}$/.test(chave)) { invalidas++; return; }
    if (tem.has(chave)) { repetidas++; return; }
    tem.add(chave);
    if ((chave.indexOf('gsk_') === 0) !== (provedor === 'groq')) outro++;  // avisa, mas guarda como a pessoa escolheu
    s.appendRow([chave, nome || '', true, testar_(chave, provedor), new Date(), provedor]);
    novas++;
  });
  const d = dadosPainel();
  d.aviso = novas + ' adicionada(s)' + (repetidas ? ', ' + repetidas + ' já estavam' : '') + (invalidas ? ', ' + invalidas + ' inválida(s)' : '') +
    (outro ? '. Atenção: ' + outro + ' não parece(m) do ' + (provedor === 'groq' ? 'Groq (gsk_…)' : 'Gemini (AIza… ou AQ.…)') : '') + '.';
  return d;
}

function alternar(linha) {
  const c = aba_().getRange(linha, 3);
  c.setValue(!ativa_(c.getValue()));
  return dadosPainel();
}

function remover(linha) {
  aba_().deleteRow(linha);
  return dadosPainel();
}

function testarTodas() {
  const s = aba_();
  linhas_().forEach(r => s.getRange(r.linha, 4, 1, 2).setValues([[testar_(r.chave, r.provedor), new Date()]]));
  return dadosPainel();
}

// ---- o que o Tinker chama (App da Web) ----

function doGet(e) {
  const p = (e && e.parameter) || {};
  // link de leitura: os últimos relatórios e o aprendizado, sem as chaves
  if (p.leitura) {
    if (p.leitura !== leitura_()) return json_({ erro: 'token' });
    return json_({ relatorios: relatorios_(Math.min(Number(p.n) || 3, 20)), memoria: memoriaGuardada_() || {} });
  }
  if (p.token !== token_()) return json_({ erro: 'token' });
  // o Tinker avisa que a chave terminada em "fim" ficou sem cota (só os 6 últimos caracteres, nunca a chave)
  if (p.fim && p.situacao) {
    const lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      const s = aba_();
      linhas_().filter(r => r.chave.slice(-6) === p.fim)
        .forEach(r => s.getRange(r.linha, 4, 1, 2).setValues([[String(p.situacao).slice(0, 80), new Date()]]));
    } finally {
      lock.releaseLock();
    }
    return json_({ ok: true });
  }
  // aprendizado guardado (o de todas as instâncias juntas)
  if (p.memoria) return json_(memoriaGuardada_() || {});
  const ativas = linhas_().filter(r => r.ativa);
  // "chaves" continua sendo o Gemini: o Tinker 2.19.0 lê só ela
  return json_({
    chaves: ativas.filter(r => r.provedor === 'gemini').map(r => r.chave),
    groq: ativas.filter(r => r.provedor === 'groq').map(r => r.chave),
  });
}

// ---- aprendizado da IA (o Tinker manda; a planilha junta o de todas as instâncias) ----

const BRUTA = 'MemoriaBruta';
const LEGIVEL = 'Aprendizado';

function memoriaGuardada_() {
  const s = SpreadsheetApp.getActive().getSheetByName(BRUTA);
  if (!s || s.getLastRow() < 1) return null;
  const t = s.getRange(1, 1, s.getLastRow(), 1).getValues().map(r => String(r[0])).join('');
  try { return JSON.parse(t); } catch (e) { return null; }
}

function guardarMemoria_(m) {
  const ss = SpreadsheetApp.getActive();
  const s = ss.getSheetByName(BRUTA) || ss.insertSheet(BRUTA);
  s.hideSheet();
  s.clear();
  const t = JSON.stringify(m);
  const partes = [];
  for (let i = 0; i < t.length; i += 45000) partes.push([t.slice(i, i + 45000)]);  // uma célula guarda até 50 mil letras
  // como texto: um pedaço que comece com = ou + não vira fórmula
  s.getRange(1, 1, partes.length, 1).setNumberFormat('@').setValues(partes);
  legivel_(m);
}

// aba para ler: lições, notas, receitas e salvos de cada jogo (a fonte de verdade é a MemoriaBruta)
function legivel_(m) {
  const ss = SpreadsheetApp.getActive();
  const s = ss.getSheetByName(LEGIVEL) || ss.insertSheet(LEGIVEL);
  s.clear();
  const rows = [['Jogo', 'Tipo', 'Texto']];
  linhas2_(m.licoes).forEach(l => rows.push(['(todos os jogos)', 'lição', l]));
  Object.keys(m.jogos || {}).forEach(pkg => {
    const g = m.jogos[pkg] || {};
    const nome = g.nome || pkg;
    linhas2_(g.notas).forEach(l => rows.push([nome, 'nota', l]));
    (g.receitas || []).forEach(r => rows.push([nome, 'receita', (r.name || '') + ' (' + ((r.steps || []).length) + ' passos)']));
    (g.salvos || []).forEach(v => rows.push([nome, 'salvo', (v.name || v.il2 || v.addr || '') + ' · ' + (v.type || '')]));
  });
  s.getRange(1, 1, rows.length, 3).setNumberFormat('@').setValues(rows);
  s.getRange(1, 1, 1, 3).setFontWeight('bold');
  s.setFrozenRows(1);
}

function linhas2_(t) {
  return String(t || '').split('\n').map(l => l.trim()).filter(l => l);
}

// junta sem repetir (a data no começo da linha não conta), como no app
function juntarLinhas_(a, b) {
  const tem = linhas2_(a);
  const vistos = new Set(tem.map(l => l.replace(/^\S+\s/, '')));
  linhas2_(b).forEach(l => { const k = l.replace(/^\S+\s/, ''); if (!vistos.has(k)) { vistos.add(k); tem.push(l); } });
  return tem.length ? tem.join('\n') + '\n' : '';
}

function juntarMemoria_(velha, nova) {
  const m = { tinker: 'memoria-ia', versao: 2, licoes: juntarLinhas_(velha.licoes, nova.licoes), jogos: {} };
  const pkgs = new Set(Object.keys(velha.jogos || {}).concat(Object.keys(nova.jogos || {})));
  pkgs.forEach(pkg => {
    const a = (velha.jogos || {})[pkg] || {}, b = (nova.jogos || {})[pkg] || {};
    const receitas = (a.receitas || []).slice();
    (b.receitas || []).forEach(r => { if (!receitas.some(x => x.id === r.id || x.name === r.name)) receitas.push(r); });
    const salvos = (a.salvos || []).slice();
    (b.salvos || []).forEach(v => {
      const igual = salvos.findIndex(x => x.id === v.id || (v.il2 && x.il2 === v.il2) || (v.name && x.name === v.name && x.type === v.type));
      if (igual < 0) salvos.push(v); else salvos[igual] = v;  // o mais novo vale (ponteiros e assinatura melhores)
    });
    m.jogos[pkg] = { nome: b.nome || a.nome || pkg, notas: juntarLinhas_(a.notas, b.notas), receitas: receitas, salvos: salvos };
  });
  return m;
}

// ---- relatórios (o Tinker manda sozinho quando algo dá errado, e quando você compartilha) ----

const RELS = 'Relatórios';
const RCOLS = ['Quando', 'Jogo', 'Versão', 'Motivo', 'Problemas', 'Relatório'];

function guardarRelatorio_(r) {
  const ss = SpreadsheetApp.getActive();
  let s = ss.getSheetByName(RELS);
  if (!s) {
    s = ss.insertSheet(RELS);
    s.getRange(1, 1, 1, RCOLS.length).setValues([RCOLS]).setFontWeight('bold');
    s.setFrozenRows(1);
  }
  const row = [new Date(), String(r.jogo || ''), String(r.versao || ''), String(r.motivo || ''), String(r.problemas || '').slice(0, 5000), String(r.texto || '').slice(0, 45000)];
  s.insertRowAfter(1);  // o mais novo em cima
  s.getRange(2, 2, 1, 5).setNumberFormat('@');
  s.getRange(2, 1, 1, RCOLS.length).setValues([row]);
  if (s.getLastRow() > 201) s.deleteRows(202, s.getLastRow() - 201);  // guarda os 200 últimos
}

function relatorios_(n) {
  const s = SpreadsheetApp.getActive().getSheetByName(RELS);
  if (!s || s.getLastRow() < 2) return [];
  const tz = Session.getScriptTimeZone();
  return s.getRange(2, 1, Math.min(n, s.getLastRow() - 1), RCOLS.length).getValues().map(r => ({
    quando: r[0] instanceof Date ? Utilities.formatDate(r[0], tz, 'dd/MM/yyyy HH:mm') : String(r[0]),
    jogo: r[1], versao: r[2], motivo: r[3], problemas: r[4], texto: r[5],
  }));
}

function doPost(e) {
  const p = (e && e.parameter) || {};
  if (p.token !== token_()) return json_({ erro: 'token' });
  if (p.relatorio) {
    let r;
    try { r = JSON.parse(e.postData.contents); } catch (err) { return json_({ erro: 'json' }); }
    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try { guardarRelatorio_(r); } finally { lock.releaseLock(); }
    return json_({ ok: true });
  }
  let nova;
  try { nova = JSON.parse(e.postData.contents); } catch (err) { return json_({ erro: 'json' }); }
  if (!nova || nova.tinker !== 'memoria-ia') return json_({ erro: 'nao e memoria do Tinker' });
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const m = juntarMemoria_(memoriaGuardada_() || { licoes: '', jogos: {} }, nova);
    m.criado = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd HH:mm');
    guardarMemoria_(m);
    return json_({ ok: true, jogos: Object.keys(m.jogos).length });
  } finally {
    lock.releaseLock();
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
