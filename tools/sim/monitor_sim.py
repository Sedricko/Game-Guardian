"""Simulador do 👁 monitor (Engine.monitorStart + daemon photo/photonoise/photodiff/list), sem celular.

Roteiro de aprendizado: cenários do fácil (jogo de teste) ao DYSMANTLE-like; cada um diz em que rodada o recurso
apareceu na lista, se o funil o manteve e quanto de memória sobrou. Uso: python3 monitor_sim.py [--fix]
"""
import random, sys

FIX = "--fix" in sys.argv
DEBUG = "--debug" in sys.argv
LIST_MAX = 3000
LIST_FIX = 20000

class Mem:
    """Palavras de 4 bytes; cada classe muda num ritmo. O recurso muda +1 a cada `ev` s."""
    def __init__(s, sc, seed):
        r = random.Random(seed); s.r = r; s.sc = sc
        n = sc["words"]; s.n = n
        s.base = 0x70000000
        s.val = {}
        pool = r.sample(range(n), sc["fast"] + sc["tick"] + sc["other_ev"] + 1)  # cada palavra é de um tipo só
        def pick(k):
            out = set(pool[-k:]); del pool[-k:]; return out
        s.fast = pick(sc["fast"])          # muda toda hora (animação): o photonoise pega
        s.tick = pick(sc["tick"])          # contador lento: +1 a cada 2..10 s (estrutura)
        s.tickp = {a: r.randint(2, 10) for a in s.tick}
        s.junk = sc["junk"]                # por segundo, quantas palavras o GC/alocação reescreve (valor qualquer)
        s.ev = pick(sc["other_ev"])        # outros eventos em degraus (vida de inimigo etc.), raros
        s.res = pool.pop(); s.val[s.res] = 12
        s.t = 0
    def get(s, a): return s.val.get(a, a * 2654435761 % 1000003)
    def step(s, secs, res_every):
        for _ in range(secs):
            s.t += 1
            for a in s.fast: s.val[a] = s.r.randint(0, 2**31)
            for a in s.tick:
                if s.t % s.tickp[a] == 0: s.val[a] = s.get(a) + 1
            for _ in range(s.junk): s.val[s.r.randrange(s.n)] = s.r.randint(0, 2**31)
            for a in s.ev:
                if s.r.random() < 0.02: s.val[a] = max(1, s.get(a) + s.r.choice([-10, -5, 1, 5]))
            # o jogador age em momentos soltos: pega (+1..+3) e às vezes gasta (−5); nunca no ritmo de um relógio
            if res_every and s.r.random() < 1 / res_every:
                s.val[s.res] = s.get(s.res) + 1 if s.sc.get("plus1") else max(1, s.get(s.res) + (-5 if s.r.random() < 0.2 else s.r.randint(1, 3)))
    def addr(s, i): return s.base + 4 * i

def run(sc, seed=1, start_wins=None):
    m = Mem(sc, seed); ev = sc["res_every"]
    def snap(wins):
        idx = range(m.n) if not wins else sorted({i for lo, hi in wins for i in range(max(0, (lo - m.base) // 4), min(m.n, (hi - m.base) // 4))})
        return {i: m.get(i) for i in idx}
    def shoot(wins):
        ph = snap(wins)
        if FIX:  # ruído = mudou nas duas metades dos 4 s (um recurso quase nunca muda duas vezes em 4 s)
            m.step(2, ev); a1 = {i for i, v in ph.items() if m.get(i) != v}; mid = {i: m.get(i) for i in a1}
            m.step(2, ev); noise = {i for i in a1 if m.get(i) != mid[i]}
            for i in a1: ph[i] = m.get(i)
            return ph, noise
        m.step(4, ev)
        noise = {i for i, v in ph.items() if m.get(i) != v}
        return ph, noise
    def windows(addrs, half):
        out = []
        for a in sorted(addrs):
            w = [max(0, a - half), a + half]
            if out and w[0] <= out[-1][1]: out[-1][1] = max(out[-1][1], w[1])
            else: out.append(w)
        return out[:512 if FIX else 64]
    wins = start_wins or []
    ph, noise = shoot(wins)
    level = 2 if wins else 0
    hist, log, first, seen, streak, shot, first_rnd, byrnd, seenv, onscreen = {}, [], None, {}, {}, 0, {}, {}, {}, {}
    empty = 0
    for rnd in range(1, sc["rounds"] + 1):
        m.step(8, ev)
        got = []
        for i, v0 in ph.items():
            if i in noise: continue
            v = m.get(i)
            if v == v0:
                continue
            if FIX:  # daemon "photodiff step": compara com a rodada anterior, guarda o valor novo, 3 rodadas seguidas mudando = estrutura
                ph[i] = v
                if not (1 <= abs(v - v0) <= 10000 and 1 <= v <= 10_000_000): noise.add(i); continue
            if len(got) < (LIST_FIX if FIX else LIST_MAX): got.append((m.addr(i), v, v0))
        for a, v, *old in got:
            if 1 <= v <= 10_000_000:
                h = hist.setdefault(a, list(old[:1]) if FIX else [])  # photostep dá o valor de antes: 1 mudança já conta
                if not h or h[-1] != v: h.append(v)
                if FIX:
                    first_rnd.setdefault(a, rnd); byrnd.setdefault(a, {})[m.t] = v
        if FIX:
            # estrutura: mudou em quase toda rodada E sempre para o mesmo lado (relógio, contador); um recurso também cai (gasta)
            # estrutura (relógio, contador): muda em quase toda rodada, sempre o mesmo tanto (±1) e para o mesmo lado.
            # recurso: muda quando o jogador age, em tamanhos diferentes, e às vezes cai (gasta)
            # ritmo = quanto mudou ÷ rodadas entre uma mudança e a outra (rodadas sem olhar não contam)
            # relógio/contador: os valores ficam numa reta no tempo (sobe sempre no mesmo ritmo); recurso não
            def struct(h, a):
                obs = sorted(byrnd[a].items())
                if len(obs) < 6: return False  # +1 em momentos soltos também parece reta com poucas mudanças
                (t0, v0), (t1, v1) = obs[0], obs[-1]
                if t1 == t0 or any(y <= x for (_, x), (_, y) in zip(obs, obs[1:])) and any(y >= x for (_, x), (_, y) in zip(obs, obs[1:])): return False
                k = (v1 - v0) / (t1 - t0)
                return all(abs(v - (v0 + k * (t - t0))) <= 1.0 for t, v in obs)
        else:
            struct = lambda h, a: rnd >= 3 and len(h) - 1 >= rnd * 8 // 10
        for a in hist: seen[a] = seen.get(a, 0) + 1
        screen = set(m.r.randint(1, 999) for _ in range(30)) | ({m.get(m.res)} if m.r.random() < sc.get("screen", 0) else set())
        for a, h in hist.items():
            if FIX and h[-1] in screen and seenv.get(a) != h[-1]: seenv[a] = h[-1]; onscreen[a] = onscreen.get(a, 0) + 1
        score = lambda a, h: len(h) + 5 * onscreen.get(a, 0) if FIX else len(h)
        allc = sorted([(a, h) for a, h in hist.items() if len(h) >= 2 and (onscreen.get(a, 0) >= 2 or not struct(h, a)) and all(1 <= abs(y - x) <= 10000 for x, y in zip(h, h[1:]))],
                       key=lambda e: -score(*e))
        cands = allc[:40] if not FIX else allc  # a lista mostra 40; o funil usa todos
        ra = m.addr(m.res)
        in_got = any(g[0] == ra for g in got); in_c = any(a == ra for a, _ in cands[:40])
        if in_c and first is None: first = rnd
        kb = (sum(hi - lo for lo, hi in wins) // 1024) if wins else len(ph) * 4 // 1024
        rk = next((k for k, (a, _) in enumerate(cands) if a == ra), None)
        why = "" if rk is not None or ra not in hist else (" struct" if struct(hist[ra], ra) else f" h={hist[ra][-5:]}")
        if rnd in (6, 30) and DEBUG:
            kinds = {}
            for a, h in cands[:rk or 40]:
                i = (a - m.base) // 4; k = 'tick' if i in m.tick else 'ev' if i in m.ev else 'fast' if i in m.fast else 'junk'
                kinds[k] = kinds.get(k, 0) + 1
            log.append(f"   rivais acima: {kinds}; top: {[ (hist[a][-8:], 'tick' if (a-m.base)//4 in m.tick else 'ev') for a,_ in cands[:6]]}; recurso: {hist.get(ra)}")
        log.append(f"rank={rk}{why} r{rnd:02d} nivel{level} mem={kb}KB diff={len(got)} cand={len(cands)} recurso:{'lista' if in_got else '-'}/{'cand' if in_c else '-'}")
        empty = 0 if cands else empty + 1
        if FIX and level > 0 and empty >= 3:  # área antiga (outro pid / jogo reiniciou): volta a olhar tudo
            level, wins = 0, []; ph, noise = shoot(wins); hist.clear(); log.append("   ↺ nada na área guardada: volta às regiões"); continue
        # só desce de nível com prova: até os estados vale tudo; daí para baixo, só quando os 512 mais fortes se destacam
        sep = level == 0 or len(cands) <= 512 or score(*cands[511]) * 2 <= score(*cands[min(39, len(cands) - 1)])
        if cands and level < 3 and (rnd % 3 == 0 if not FIX else rnd - shot >= 3 and sep):
            level += 1
            # do país para os estados vale tudo que mudou por evento; daí para baixo, os 512 mais fortes
            wins = windows([a for a, _ in (cands if FIX and level == 1 else cands[:512 if FIX else 40])], {1: 512 * 1024, 2: 4096, 3: 512}[level])
            ph, noise = shoot(wins); shot = rnd; streak.clear()
            if FIX:  # o que ficou fora das faixas não atualiza mais: sai do histórico
                for a in [a for a in hist if not any(lo <= a < hi for lo, hi in wins)]: del hist[a]
    ok = any(a == m.addr(m.res) for a, _ in cands[:40])
    return ok, first, log, wins

SCEN = [
    dict(name="0 missão 17: só +1 lenha, em momentos soltos", words=400_000, fast=2000, tick=200, junk=20, other_ev=50, res_every=8, rounds=20, plus1=True, screen=0.9),
    # nome, palavras (×4 B), animação, contadores, lixo/s, outros eventos, recurso a cada N s, rodadas
    dict(name="1 jogo de teste (nível 17)", words=400_000, fast=2000, tick=200, junk=20, other_ev=50, res_every=6, rounds=30),
    dict(name="2 jogo médio, recurso a cada 20 s", words=2_000_000, fast=20000, tick=2000, junk=300, other_ev=500, res_every=20, rounds=30),
    dict(name="3 DYSMANTLE-like (muito lixo do GC)", words=6_000_000, fast=60000, tick=8000, junk=3000, other_ev=3000, res_every=15, rounds=30, screen=0.5),
    dict(name="4 DYSMANTLE-like, recurso raro (40 s)", words=6_000_000, fast=60000, tick=8000, junk=3000, other_ev=3000, res_every=40, rounds=30, screen=0.5),
]

def bench(seeds):
    """Taxa de acerto por cenário em várias sementes (o número que vai para a planilha)."""
    tot = 0
    for sc in SCEN + [dict(SCEN[2], name="5 área guardada velha (jogo reiniciou)", stale=True)]:
        hits = []
        for sd in range(1, seeds + 1):
            st = [[0x70000000 + 4 * 1_999_000, 0x70000000 + 4 * 1_999_900]] if sc.get("stale") and not FIX else None
            ok, first, _, _ = run(sc, seed=sd, start_wins=st); hits.append((ok, first))
        n = sum(o for o, _ in hits); tot += n
        print(f"{sc['name']}: {n}/{seeds}  (rodada em que apareceu: {[f for _, f in hits]})")
    print(f"TOTAL {tot}/{6 * seeds} {'(com as correções)' if FIX else '(2.82.0)'}")

if __name__ == "__main__" and "--seeds" in sys.argv:
    bench(int(sys.argv[sys.argv.index("--seeds") + 1])); sys.exit()
if __name__ == "__main__":
    score = 0
    for sc in SCEN:
        ok, first, log, wins = run(sc)
        score += ok
        print(f"\n== {sc['name']}: {'ACHOU' if ok else 'NÃO achou'} (primeira vez na lista: rodada {first}) ==")
        print("\n".join(log))
    # 5: segunda vez no jogo, o jogo reiniciou (endereços mudaram): começa na área guardada, que agora é velha
    sc = dict(SCEN[2], name="5 área guardada velha (jogo reiniciou)")
    stale = [[0x70000000 + 4 * 1_999_000, 0x70000000 + 4 * 1_999_900]]
    # a área guardada vale só para o mesmo pid; o jogo reiniciou (outro pid) → começa do zero
    ok, first, log, _ = run(sc, seed=7, start_wins=None if FIX else stale); score += ok
    print(f"\n== {sc['name']}: {'ACHOU' if ok else 'NÃO achou'} =="); print("\n".join(log))
    print(f"\nPONTOS: {score}/6 {'(com as correções)' if FIX else '(como está na 2.82.0)'}")
