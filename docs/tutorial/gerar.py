# Gera a Ajuda do app e o tutorial em PDF a partir de ajuda.py (fonte única: o app e o PDF nunca ficam diferentes).
# Uso: python3 -I docs/tutorial/gerar.py   (precisa de reportlab e das fontes DejaVu)
import datetime
import pathlib
import re

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

AQUI = pathlib.Path(__file__).resolve().parent
RAIZ = AQUI.parent.parent
TELA = RAIZ / "app/src/main/java/dev/tinker/app/SettingsTab.kt"
ns = {}
exec((AQUI / "ajuda.py").read_text(encoding="utf-8"), ns)
AJUDA = ns["AJUDA"]
VERSAO = re.search(r'versionName = "([^"]+)"', (RAIZ / "app/build.gradle.kts").read_text()).group(1)

# Para que serve cada aba (a ordem é a do app)
ABAS = {
    "Começo": "Abrir o Tinker, escolher o jogo e usar o menu flutuante sobre o jogo.",
    "Busca": "Achar onde o jogo guarda um número: buscar, refinar, editar e congelar.",
    "Ferramentas": "Atalhos prontos para casos comuns, logo abaixo da busca: uma ferramenta aberta por vez.",
    "Salvos": "O que você achou fica aqui: congelar, dar nome, receitas, patches e voltar sozinho ao reabrir.",
    "Memória": "Ver a memória em volta, a ficha do objeto (campos com nome), métodos e funções do jogo.",
    "Registro": "O que aconteceu e por quê: quedas do jogo e o relatório para investigar.",
    "Ajustes": "Assistente (IA), menu flutuante, busca, ponteiros, backup e atualização.",
}
assert set(ABAS) == {s for s, _, _ in AJUDA}, "toda seção da Ajuda precisa de uma linha em ABAS"


def kotlin():
    def k(t):
        return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'
    linhas = ["// AJUDA: gerada por docs/tutorial/gerar.py a partir de docs/tutorial/ajuda.py (edite lá, não aqui)",
              "private val HELP = listOf("]
    linhas += [f"    Triple({k(s)}, {k(t)}, {k(b)})," for s, t, b in AJUDA]
    linhas += [")", "// FIM AJUDA"]
    src = TELA.read_text(encoding="utf-8")
    novo, n = re.subn(r"// AJUDA:.*?// FIM AJUDA", lambda _: "\n".join(linhas), src, flags=re.S)
    assert n == 1, "marcas // AJUDA e // FIM AJUDA não achadas em SettingsTab.kt"
    TELA.write_text(novo, encoding="utf-8")


# emojis que a fonte do PDF não tem: viram palavra curta (ou somem, quando o nome já vem ao lado)
EMOJI = {"🔒": "cadeado", "🎯": "", "🧭": "", "⏩": "", "⏱️": "", "⏱": "", "🛒": "", "🔓": "", "🧮": "", "🧰": "",
         "📍": "", "💾": "", "🌙": "", "👁": "olho", "✨": "estrela", "⚙": "", "️": ""}


def pdf():
    pdfmetrics.registerFont(TTFont("DV", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    pdfmetrics.registerFont(TTFont("DVB", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
    face = pdfmetrics.getFont("DV").face

    def limpo(t):
        for e, w in EMOJI.items():
            t = t.replace(e, w)
        t = "".join(c for c in t if ord(c) < 128 or ord(c) in face.charToGlyph)
        t = re.sub(r"\(\s*\)", "", re.sub(r" {2,}", " ", t)).strip()
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    azul = colors.HexColor("#0B6E6E")
    st = {
        "capa": ParagraphStyle("capa", fontName="DVB", fontSize=30, leading=36, textColor=azul),
        "sub": ParagraphStyle("sub", fontName="DV", fontSize=13, leading=18, textColor=colors.HexColor("#444444")),
        "h1": ParagraphStyle("h1", fontName="DVB", fontSize=18, leading=24, textColor=azul, spaceBefore=6, spaceAfter=4),
        "intro": ParagraphStyle("intro", fontName="DV", fontSize=10.5, leading=15, textColor=colors.HexColor("#555555"), spaceAfter=8),
        "h2": ParagraphStyle("h2", fontName="DVB", fontSize=12, leading=16, spaceBefore=8, spaceAfter=2),
        "p": ParagraphStyle("p", fontName="DV", fontSize=10.5, leading=15),
        "cel": ParagraphStyle("cel", fontName="DV", fontSize=10, leading=13),
        "celb": ParagraphStyle("celb", fontName="DVB", fontSize=10, leading=13),
    }
    hoje = datetime.date.today().strftime("%d/%m/%Y")

    def rodape(c, d):
        c.saveState()
        c.setFont("DV", 8)
        c.setFillColor(colors.grey)
        c.drawString(2 * cm, 1.2 * cm, f"Tinker {VERSAO} · tutorial · {hoje}")
        c.drawRightString(A4[0] - 2 * cm, 1.2 * cm, str(d.page))
        c.restoreState()

    h = []
    h += [Spacer(1, 6 * cm), Paragraph("Tinker", st["capa"]), Spacer(1, 4),
          Paragraph("Tutorial completo: todas as opções, para que servem e como usar", st["sub"]), Spacer(1, 10),
          Paragraph(f"Versão {VERSAO} · {hoje}", st["sub"]), Spacer(1, 2 * cm),
          Paragraph("Editor de memória para jogos offline de um jogador (como o GameGuardian e o Cheat Engine). "
                    "Precisa de root. Não use em jogo online.", st["p"]), PageBreak()]

    h.append(Paragraph("Onde fica cada coisa", st["h1"]))
    h.append(Paragraph("O Tinker é organizado como um canivete: o que se usa toda hora fica no topo; o resto, recolhido "
                       "ou numa aba própria.", st["intro"]))
    linhas = [[Paragraph("Parte", st["celb"]), Paragraph("Para que serve", st["celb"]), Paragraph("Opções", st["celb"])]]
    for sec, desc in ABAS.items():
        itens = ", ".join(limpo(t) for s, t, _ in AJUDA if s == sec)
        linhas.append([Paragraph(sec, st["celb"]), Paragraph(limpo(desc), st["cel"]), Paragraph(itens, st["cel"])])
    tab = Table(linhas, colWidths=[3.3 * cm, 5.9 * cm, 7.8 * cm], repeatRows=1)
    tab.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#E3F1F1")), ("VALIGN", (0, 0), (-1, -1), "TOP"),
                             ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#BBBBBB")), ("TOPPADDING", (0, 0), (-1, -1), 4),
                             ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]))
    h += [tab, Spacer(1, 12)]
    h.append(Paragraph("Caminho mais rápido", st["h2"]))
    for passo in ["1. Abra o Tinker: a bolinha aparece sobre o jogo. Toque nela e escolha o jogo.",
                  "2. Digite o número da tela e toque em Nova busca.",
                  "3. Mude o número no jogo, digite o novo e toque em Refinar, até sobrar 1.",
                  "4. Toque no resultado: Novo valor, ou ❄ Congelar. Ele vai para Salvos e volta sozinho quando o jogo reabrir.",
                  "5. Não achou? Use 🧭 Fácil, 🎯 Achar sozinho ou 🧮 Disfarçado nas Ferramentas."]:
        h.append(Paragraph(limpo(passo), st["p"]))
    h.append(PageBreak())

    for sec, desc in ABAS.items():
        h += [Paragraph(sec, st["h1"]), Paragraph(limpo(desc), st["intro"])]
        for s, t, b in AJUDA:
            if s == sec:
                h.append(KeepTogether([Paragraph(limpo(t), st["h2"]), Paragraph(limpo(b), st["p"])]))
        h.append(Spacer(1, 10))

    saida = AQUI / "Tinker-tutorial.pdf"
    SimpleDocTemplate(str(saida), pagesize=A4, leftMargin=2 * cm, rightMargin=2 * cm, topMargin=2 * cm, bottomMargin=2 * cm,
                      title=f"Tinker {VERSAO} — Tutorial", author="Tinker").build(h, onFirstPage=rodape, onLaterPages=rodape)
    return saida


if __name__ == "__main__":
    kotlin()
    print(pdf())
