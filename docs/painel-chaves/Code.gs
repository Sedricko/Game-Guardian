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
  const ativas = linhas_().filter(r => r.ativa);
  // "chaves" continua sendo o Gemini: o Tinker 2.19.0 lê só ela
  return json_({
    chaves: ativas.filter(r => r.provedor === 'gemini').map(r => r.chave),
    groq: ativas.filter(r => r.provedor === 'groq').map(r => r.chave),
  });
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
