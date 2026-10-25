// Painel de chaves do Tinker: planilha privada com as chaves do Gemini e um link (App da Web) que o Tinker lê.
// A planilha nunca fica pública: o link só devolve as chaves ativas para quem tem o token.

const ABA = 'Chaves';

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Tinker')
    .addItem('Abrir painel', 'abrirPainel')
    .addItem('Gerar novo token (invalida o link antigo)', 'novoToken')
    .addToUi();
}

function abrirPainel() {
  SpreadsheetApp.getUi().showSidebar(HtmlService.createHtmlOutputFromFile('Painel').setTitle('Chaves do Tinker'));
}

function aba_() {
  const ss = SpreadsheetApp.getActive();
  let s = ss.getSheetByName(ABA);
  if (!s) {
    s = ss.insertSheet(ABA);
    s.getRange(1, 1, 1, 5).setValues([['Chave', 'Nome', 'Ativa', 'Situação', 'Atualizado']]).setFontWeight('bold');
    s.setFrozenRows(1);
  }
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
  return v !== false && String(v).toUpperCase() !== 'FALSE' && String(v).toUpperCase() !== 'NÃO';
}

function linhas_() {
  const s = aba_();
  const n = s.getLastRow() - 1;
  if (n < 1) return [];
  return s.getRange(2, 1, n, 5).getValues()
    .map((r, i) => ({ linha: i + 2, chave: String(r[0]).trim(), nome: String(r[1]), ativa: ativa_(r[2]), situacao: String(r[3]), atualizado: r[4] }))
    .filter(r => r.chave);
}

function testar_(chave) {
  // lista de modelos: confere a chave sem gastar a cota de respostas
  const r = UrlFetchApp.fetch('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1',
    { headers: { 'x-goog-api-key': chave }, muteHttpExceptions: true });
  const c = r.getResponseCode();
  return c === 200 ? 'ok' : c === 429 ? 'sem cota agora' : 'recusada (' + c + ')';
}

// ---- chamadas do painel ----

function dadosPainel() {
  const url = ScriptApp.getService().getUrl();
  const tz = Session.getScriptTimeZone();
  return {
    chaves: linhas_().map(r => ({
      linha: r.linha, fim: '…' + r.chave.slice(-6), nome: r.nome, ativa: r.ativa, situacao: r.situacao,
      atualizado: r.atualizado instanceof Date ? Utilities.formatDate(r.atualizado, tz, 'dd/MM HH:mm') : '',
    })),
    link: url ? url + '?token=' + token_() : '',
  };
}

function adicionar(chave, nome) {
  chave = String(chave || '').replace(/\s/g, '');
  if (!/^[A-Za-z0-9._-]{20,200}$/.test(chave)) throw new Error('Isso não parece uma chave (AIza… ou AQ.…).');
  if (linhas_().some(r => r.chave === chave)) throw new Error('Essa chave já está na lista.');
  aba_().appendRow([chave, nome || '', true, testar_(chave), new Date()]);
  return dadosPainel();
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
  linhas_().forEach(r => s.getRange(r.linha, 4, 1, 2).setValues([[testar_(r.chave), new Date()]]));
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
  return json_({ chaves: linhas_().filter(r => r.ativa).map(r => r.chave) });
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
