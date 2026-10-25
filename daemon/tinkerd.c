// tinkerd: daemon de memória do Tinker. Roda como root e fala por stdin/stdout,
// um comando por linha; cada resposta termina com "ok ..." ou "err ...".
// Linhas de texto livre (ex.: crashlog) começam com "| " para não confundir o protocolo.
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64  // pread em endereços acima de 2 GB mesmo num build 32 bits
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/statvfs.h>
#include <sys/uio.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

static const struct { const char *name; int size, isfloat; } TYPES[] = {
    {"i8", 1, 0}, {"i16", 2, 0}, {"i32", 4, 0}, {"i64", 8, 0}, {"f32", 4, 1}, {"f64", 8, 1},
};
#define NTYPES (int)(sizeof TYPES / sizeof TYPES[0])
#define T_I32 2
#define T_I64 3
#define T_F32 4
#define T_F64 5

// Regiões no estilo do GameGuardian. Só as graváveis entram na busca.
static const char *REGIONS[] = {"Jh", "J", "Ch", "Ca", "Cd", "Cb", "A", "S", "As", "O", "V"};
#define NREGIONS (int)(sizeof REGIONS / sizeof REGIONS[0])
#define R_V 10
static unsigned ranges = ~(1u << R_V);  // padrão: tudo menos memória de vídeo

static pid_t target;

// Valores para o autoteste do app: um daemon à parte se liga a ele mesmo e testa busca, refino, escrita, congelamento e
// AOB aqui, sem encostar no jogo. volatile e fora de função: ficam na memória gravável do próprio daemon.
volatile int32_t tinker_selftest_i = 271828182;
volatile char tinker_selftest_aob[] = "TINKER-AUTOTESTE-AOB";
static int memfd = -1;

// old: o valor na última busca/refino, para "aumentou/diminuiu/mudou"
typedef struct { uint64_t addr, old; uint16_t type; uint8_t region; } hit_t;
// shortcut: resultados num vetor em RAM (24 bytes cada); acima de LIST_MAX a busca desconhecida fica no arquivo
static size_t list_max = 2000000;  // TINKER_LIST_MAX muda (só para os testes passarem pelo caminho do arquivo)
static hit_t *res;
static size_t nres, cap;
// Estado anterior ao último refino, para o "Desfazer".
static hit_t *prev;
static size_t nprev, capprev;

// Um valor exato ou uma faixa "100~200" (range=1: só serve para buscar e refinar, nunca para escrever).
typedef struct { uint64_t raw; double f; int range; double lo, hi; int64_t ilo, ihi; } value_t;


// process_vm_* não para o jogo; /proc/pid/mem cobre VMs/kernels que o bloqueiam.
static int read_mem(uint64_t addr, void *buf, size_t len) {
    struct iovec l = {buf, len}, r = {(void *)(uintptr_t)addr, len};
    if (process_vm_readv(target, &l, 1, &r, 1, 0) == (ssize_t)len) return 0;
    return memfd >= 0 && pread(memfd, buf, len, (off_t)addr) == (ssize_t)len ? 0 : -1;
}

static int write_mem(uint64_t addr, const void *buf, size_t len) {
    struct iovec l = {(void *)buf, len}, r = {(void *)(uintptr_t)addr, len};
    if (process_vm_writev(target, &l, 1, &r, 1, 0) == (ssize_t)len) return 0;
    return memfd >= 0 && pwrite(memfd, buf, len, (off_t)addr) == (ssize_t)len ? 0 : -1;
}

// Cifrado (xi32/xf32): como no Anti-Cheat Toolkit do Unity, a chave fica em addr e valor^chave em addr+4.
// xi32@N / xf32@N: como o "150X4" do GameGuardian, valor^chave em addr e a chave N bytes ao lado (N de -64 a 64, de 4 em 4).
// i32x2 / i64x2: inteiro guardado em dobro (Dart/Flutter e JavaScript: 100 fica 200 na memória).
// O tipo guarda as marcas por cima do tipo base; a distância da chave vai nos bits de cima.
#define XOR 0x40
#define TAG 0x20
#define B(t) ((t) & 0x1f)
static int xoff(int t) { return (t >> 8) ? ((t >> 8) - 17) * 4 : 0; }  // 0 = o cifrado de sempre (chave antes)

static int find_type(const char *s) {
    if (!strcmp(s, "xi32")) return T_I32 | XOR;
    if (!strcmp(s, "xf32")) return T_F32 | XOR;
    if (!strcmp(s, "xi64")) return T_I64 | XOR;  // ObscuredLong/ObscuredDouble: chave de 8 bytes em addr, valor^chave em addr+8
    if (!strcmp(s, "xf64")) return T_F64 | XOR;
    if (!strcmp(s, "i32x2")) return T_I32 | TAG;
    if (!strcmp(s, "i64x2")) return T_I64 | TAG;
    if ((!strncmp(s, "xi32@", 5) || !strncmp(s, "xf32@", 5)) && s[5]) {
        char *end;
        long n = strtol(s + 5, &end, 10);
        if (*end || !n || n % 4 || n < -64 || n > 64) return -1;
        return (s[1] == 'i' ? T_I32 : T_F32) | XOR | (int)((n / 4 + 17) << 8);
    }
    for (int i = 0; i < NTYPES; i++) if (!strcmp(s, TYPES[i].name)) return i;
    return -1;
}

static const char *tname(int t) {
    static char buf[16];
    if (t & TAG) return B(t) == T_I32 ? "i32x2" : "i64x2";
    if (!(t & XOR)) return TYPES[t].name;
    if (!xoff(t)) return B(t) == T_I32 ? "xi32" : B(t) == T_F32 ? "xf32" : B(t) == T_I64 ? "xi64" : "xf64";
    snprintf(buf, sizeof buf, "%s@%d", B(t) == T_I32 ? "xi32" : "xf32", xoff(t));
    return buf;
}

// Em dobro: só vale número par (o bit de baixo marca "não é número" no Dart e no JavaScript).
static int untag(int t, unsigned char *p) {
    if (B(t) == T_I32) { int32_t x; memcpy(&x, p, 4); if (x & 1) return -1; x >>= 1; memcpy(p, &x, 4); }
    else { int64_t x; memcpy(&x, p, 8); if (x & 1) return -1; x >>= 1; memcpy(p, &x, 8); }
    return 0;
}

// chave de verdade é um número sorteado, grande; 0 ou números pequenos dariam pares falsos o tempo todo
static int xor_key(int32_t k) { return k >= 0x10000 || k <= -0x10000; }

static int parse_one(int t, const char *s, value_t *v);

static int parse_value(int t, const char *s, value_t *v) {
    const char *tilde = strchr(s, '~');
    v->range = 0;
    if (!tilde) return parse_one(t, s, v);
    char a[64];
    snprintf(a, sizeof a, "%.*s", (int)(tilde - s), s);
    value_t lo, hi;
    if (!parse_one(t, a, &lo) || !parse_one(t, tilde + 1, &hi)) return 0;
    v->range = 1;
    v->lo = fmin(lo.f, hi.f);
    v->hi = fmax(lo.f, hi.f);
    v->ilo = lo.ilo < hi.ilo ? lo.ilo : hi.ilo;
    v->ihi = lo.ilo < hi.ilo ? hi.ilo : lo.ilo;
    return 1;
}

static int parse_one(int t, const char *s, value_t *v) {
    char *end;
    v->range = 0;
    if (TYPES[t].isfloat) {
        v->f = strtod(s, &end);
        v->ilo = 0;
    } else {
        double d = strtod(s, &end);
        if (end == s || (*end && *end != '\n') || d != floor(d)) return 0;  // "1.5" não é inteiro
        int bits = TYPES[t].size * 8;
        // não cabe no tipo: antes 10000000000 num Dword virava 1410065408 (o resto) e estragava o valor do jogo
        if (bits < 64 ? d < -ldexp(1, bits - 1) || d > ldexp(1, bits) - 1 : d < -ldexp(1, 63) || d >= ldexp(1, 64)) return 0;
        v->f = d;
        v->raw = d >= ldexp(1, 63) ? strtoull(s, &end, 0) : (uint64_t)strtoll(s, &end, 0);
        v->ilo = (int64_t)v->raw;
        if (bits < 64) v->raw &= (1ULL << bits) - 1;  // -1 e 4294967295 viram o mesmo dword
    }
    return end != s && (*end == 0 || *end == '\n');
}

// Vários valores/faixas separados por ";" (ex.: 20000;50000 ou 100;200~300): casa se qualquer um casar.
#define MAXV 16
typedef struct { value_t v[MAXV]; int n; uint8_t idx[MAXV]; } vset_t;  // idx: posição do valor no texto (busca em grupo)

static int matches(int t, const unsigned char *p, const value_t *v);

// lenient: um item que não serve no tipo (99.5 num Dword) só fica de fora dele, em vez de excluir o tipo
static int parse_set_as(int t, const char *s, vset_t *set, int lenient) {
    char buf[256], *save = NULL;
    snprintf(buf, sizeof buf, "%s", s);
    set->n = 0;
    int pos = 0;
    for (char *tok = strtok_r(buf, ";", &save); tok; tok = strtok_r(NULL, ";", &save), pos++) {
        if (pos == MAXV) return 0;
        if (!parse_value(t, tok, &set->v[set->n])) { if (lenient) continue; return 0; }  // senão: um item que não serve exclui o tipo
        set->idx[set->n++] = (uint8_t)pos;
    }
    return set->n > 0;
}

static int parse_set(int t, const char *s, vset_t *set) { return parse_set_as(t, s, set, 0); }

static int count_items(const char *s) {
    int n = 1;
    for (; *s; s++) n += *s == ';';
    return n;
}

static int matches_set(int t, const unsigned char *p, const vset_t *set) {
    for (int i = 0; i < set->n; i++) if (matches(t, p, &set->v[i])) return 1;
    return 0;
}

static int matches(int t, const unsigned char *p, const value_t *v) {
    if (v->range) {
        if (t == T_F32) { float x; memcpy(&x, p, 4); return x >= v->lo && x <= v->hi; }
        if (t == T_F64) { double x; memcpy(&x, p, 8); return x >= v->lo && x <= v->hi; }
        int sz = TYPES[t].size;
        uint64_t x = 0; memcpy(&x, p, sz);
        if (sz < 8 && (x >> (sz * 8 - 1))) x |= ~0ULL << (sz * 8);  // com sinal: -5~5 funciona
        return (int64_t)x >= v->ilo && (int64_t)x <= v->ihi;
    }
    if (t == T_F32) {
        float x; memcpy(&x, p, 4);
        return fabs(x - v->f) <= 1e-6 * fmax(1, fabs(v->f));  // "1.1" digitado vs 1.1f guardado
    }
    if (t == T_F64) {
        double x; memcpy(&x, p, 8);
        return fabs(x - v->f) <= 1e-9 * fmax(1, fabs(v->f));
    }
    uint64_t x = 0; memcpy(&x, p, TYPES[t].size);
    return x == v->raw;
}

static void encode(int t, const value_t *v, unsigned char *out) {
    if (t == T_F32) { float x = (float)v->f; memcpy(out, &x, 4); }
    else if (t == T_F64) memcpy(out, &v->f, 8);
    else memcpy(out, &v->raw, TYPES[t].size);
}

static void format(int t, const unsigned char *p, char *out) {
    int sz = TYPES[t].size;
    if (t == T_F32) { float x; memcpy(&x, p, 4); sprintf(out, "%.9g", x); return; }
    if (t == T_F64) { double x; memcpy(&x, p, 8); sprintf(out, "%.17g", x); return; }
    uint64_t x = 0; memcpy(&x, p, sz);
    if (sz < 8 && (x >> (sz * 8 - 1))) x |= ~0ULL << (sz * 8);  // estende o sinal
    sprintf(out, "%" PRId64, (int64_t)x);
}

static int region_of(const char *perms, const char *name) {
    if (strstr(name, "/dev/kgsl") || strstr(name, "/dev/mali") || strstr(name, "/dev/dri") || strstr(name, "dmabuf")) return R_V;
    if (strstr(name, "dalvik-main space") || strstr(name, "dalvik-alloc space") || strstr(name, "dalvik-large object") ||
        strstr(name, "dalvik-zygote space") || strstr(name, "dalvik-non moving space")) return 0;           // Jh
    if (strstr(name, "dalvik")) return 1;                                                                   // J
    if (!strcmp(name, "[heap]")) return 2;                                                                  // Ch
    if (!strncmp(name, "[anon:libc_malloc", 17) || !strncmp(name, "[anon:scudo:", 12) ||
        !strncmp(name, "[anon:jemalloc", 14)) return 3;                                                     // Ca
    if (strstr(name, ".so") && name[0] == '/') return 4;                                                    // Cd
    if (!strcmp(name, "[anon:.bss]")) return 5;                                                             // Cb
    if (!name[0]) return 6;                                                                                 // A
    if (!strncmp(name, "[stack", 6) || !strncmp(name, "[anon:stack", 11) || !strncmp(name, "[anon:thread", 12)) return 7;  // S
    if (!strncmp(name, "/dev/ashmem", 11)) return 8;                                                        // As
    (void)perms;
    return 9;                                                                                               // O
}

static int region_exec;  // 1: percorre o código (r-x) em vez das regiões graváveis, para a busca AOB no código
static int region_all;   // 1: tudo que dá para ler (os nomes das classes IL2CPP ficam no metadata, só leitura)

// Chama fn para cada região gravável do alvo. Devolve -1 se não abriu o maps.
static int each_region(void (*fn)(uint64_t s, uint64_t e, int region, void *ctx), void *ctx) {
    char path[64], line[1024];
    snprintf(path, sizeof path, "/proc/%d/maps", target);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    while (fgets(line, sizeof line, f)) {
        unsigned long long s, e;
        char perms[8];
        int off = 0;
        if (sscanf(line, "%llx-%llx %7s %*s %*s %*s %n", &s, &e, perms, &off) < 3) continue;
        if (perms[0] != 'r' || (!region_all && (region_exec ? perms[2] != 'x' : perms[1] != 'w'))) continue;
        char *name = off ? line + off : line + strlen(line);
        name[strcspn(name, "\n")] = 0;
        fn(s, e, region_of(perms, name), ctx);
    }
    fclose(f);
    return 0;
}

static int alive(void) { return target > 0 && !(kill(target, 0) && errno == ESRCH); }

typedef struct { hit_t *h; size_t n, cap; } hits_t;

static void hpush(hits_t *v, hit_t h) {
    if (v->n == v->cap) {
        v->cap = v->cap ? v->cap * 2 : 256;
        v->h = realloc(v->h, v->cap * sizeof *v->h);
        if (!v->h) { puts("err sem memória"); exit(1); }
    }
    v->h[v->n++] = h;
}

static void push(hit_t h) {
    hits_t v = {res, nres, cap};
    hpush(&v, h);
    res = v.h; nres = v.n; cap = v.cap;
}

static uint64_t bits(const unsigned char *p, int sz) { uint64_t x = 0; memcpy(&x, p, sz); return x; }

// Memória a percorrer, em pedaços de 1 MB; off = posição no arquivo da busca desconhecida.
#define CHUNK (1u << 20)
typedef struct { uint64_t addr, off; uint32_t len; uint8_t region; } chunk_t;
typedef struct { chunk_t *c; size_t n, cap; uint64_t total; unsigned want; } chunks_t;

static void add_chunks(uint64_t s, uint64_t e, int region, void *ctx) {
    chunks_t *cs = ctx;
    if (!(cs->want & (1u << region))) return;
    for (uint64_t a = s; a < e; a += CHUNK) {
        if (cs->n == cs->cap) {
            cs->cap = cs->cap ? cs->cap * 2 : 1024;
            cs->c = realloc(cs->c, cs->cap * sizeof *cs->c);
            if (!cs->c) { puts("err sem memória"); exit(1); }
        }
        uint32_t len = e - a < CHUNK ? (uint32_t)(e - a) : CHUNK;
        cs->c[cs->n++] = (chunk_t){a, cs->total, len, (uint8_t)region};
        cs->total += len;
    }
}

// Roda fn em cada pedaço usando todos os núcleos (até 8). Cada thread tem um buffer de 2 MB.
typedef void (*chunk_fn)(size_t i, unsigned char *buf, void *ctx);
typedef struct { chunk_fn fn; void *ctx; size_t n, next; } pool_t;

static void *worker(void *arg) {
    pool_t *p = arg;
    unsigned char *buf = malloc(2 * CHUNK);
    if (!buf) return NULL;  // as outras threads fazem o trabalho
    for (size_t i; (i = __atomic_fetch_add(&p->next, 1, __ATOMIC_RELAXED)) < p->n;) p->fn(i, buf, p->ctx);
    free(buf);
    return NULL;
}

static void parallel(size_t n, chunk_fn fn, void *ctx) {
    pool_t p = {fn, ctx, n, 0};
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    int nt = cpus < 1 ? 1 : cpus > 8 ? 8 : (int)cpus, started = 0;
    pthread_t th[8];
    for (int k = 1; k < nt; k++) if (!pthread_create(&th[started], NULL, worker, &p)) started++;
    worker(&p);
    for (int k = 0; k < started; k++) pthread_join(th[k], NULL);
}

// Junta os achados de cada pedaço em res, na ordem dos endereços.
static void collect(hits_t *per, size_t n) {
    nres = 0;
    for (size_t i = 0; i < n; i++) {
        for (size_t k = 0; k < per[i].n; k++) push(per[i].h[k]);
        free(per[i].h);
    }
    free(per);
}

// Leitura com cache de 64 KB: o refino lê resultados vizinhos sem uma chamada ao kernel para cada um.
#define CBLOCK 65536
static unsigned char cbuf[CBLOCK];
static uint64_t cbase = 1;
static int cok;

static int cread(uint64_t a, unsigned char *out, int sz) {
    uint64_t b = a & ~(uint64_t)(CBLOCK - 1);
    if (b != cbase) { cbase = b; cok = !read_mem(b, cbuf, CBLOCK); }
    if (cok && a + sz <= b + CBLOCK) { memcpy(out, cbuf + (a - b), sz); return 0; }
    return read_mem(a, out, sz);  // bloco cruza memória sem mapa, ou o valor passa do fim do bloco
}

// Lê o valor como o jogo vê (no cifrado: chave ^ valor guardado).
static int load(uint64_t a, int t, unsigned char *out, int cached) {
    if (t & TAG) return (cached ? cread(a, out, TYPES[B(t)].size) : read_mem(a, out, TYPES[B(t)].size)) || untag(t, out) ? -1 : 0;
    if (!(t & XOR)) return cached ? cread(a, out, TYPES[t].size) : read_mem(a, out, TYPES[t].size);
    if (TYPES[B(t)].size == 8) {  // 8 bytes: chave e depois o valor trancado
        uint64_t q[2];
        if (cached ? cread(a, (unsigned char *)q, 16) : read_mem(a, q, 16)) return -1;
        q[0] ^= q[1];
        memcpy(out, q, 8);
        return 0;
    }
    uint32_t w[2];
    if (xoff(t)) {
        if (read_mem(a, &w[0], 4) || read_mem(a + xoff(t), &w[1], 4)) return -1;
        w[0] ^= w[1];
        memcpy(out, w, 4);
        return 0;
    }
    if (cached ? cread(a, (unsigned char *)w, 8) : read_mem(a, w, 8)) return -1;
    w[0] ^= w[1];
    memcpy(out, w, 4);
    return 0;
}

// Escreve como o jogo escreveria: no cifrado, tranca o valor novo com a chave atual.
static int store(uint64_t a, int t, const unsigned char *val) {
    if (t & TAG) {
        int64_t x = 0;
        if (B(t) == T_I32) { int32_t y; memcpy(&y, val, 4); y = (int32_t)((uint32_t)y << 1); return write_mem(a, &y, 4); }
        memcpy(&x, val, 8);
        x = (int64_t)((uint64_t)x << 1);
        return write_mem(a, &x, 8);
    }
    if (!(t & XOR)) return write_mem(a, val, TYPES[t].size);
    if (TYPES[B(t)].size == 8) {
        uint64_t k8, v8;
        if (read_mem(a, &k8, 8)) return -1;
        memcpy(&v8, val, 8);
        v8 ^= k8;
        return write_mem(a + 8, &v8, 8);
    }
    uint32_t key, v;
    if (xoff(t)) {
        if (read_mem(a + xoff(t), &key, 4)) return -1;
        memcpy(&v, val, 4);
        v ^= key;
        return write_mem(a, &v, 4);
    }
    if (read_mem(a, &key, 4)) return -1;
    memcpy(&v, val, 4);
    v ^= key;
    return write_mem(a + 4, &v, 4);
}

static void count_region(uint64_t s, uint64_t e, int region, void *ctx) {
    unsigned long long *c = ctx;
    c[region * 2]++;
    c[region * 2 + 1] += e - s;
}

static void cmd_regions(void) {
    unsigned long long c[NREGIONS * 2] = {0};
    if (each_region(count_region, c)) { puts("err não leu o mapa de memória"); return; }
    for (int i = 0; i < NREGIONS; i++)
        if (c[i * 2]) printf("%s %llu %llu %d\n", REGIONS[i], c[i * 2], c[i * 2 + 1], !!(ranges & (1u << i)));
    puts("ok");
}

static int set_ranges(const char *list) {
    if (!strcmp(list, "all")) { ranges = ~(1u << R_V); return 1; }
    unsigned m = 0;
    char buf[128];
    snprintf(buf, sizeof buf, "%s", list);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        int found = 0;
        for (int i = 0; i < NREGIONS; i++) if (!strcmp(tok, REGIONS[i])) { m |= 1u << i; found = 1; }
        if (!found) return 0;
    }
    ranges = m;
    return 1;
}

typedef struct { int types[2 * NTYPES], ntypes, step; vset_t v[NTYPES]; chunks_t cs; hits_t *out; } scan_t;

static void scan_chunk(size_t ci, unsigned char *buf, void *ctx) {
    scan_t *sc = ctx;
    const chunk_t *c = &sc->cs.c[ci];
    if (read_mem(c->addr, buf, c->len)) return;
    // um resultado por endereço: no Auto, Dword/Float ganham de Qword/Double,
    // senão o Qword no mesmo lugar sobrescreveria os 4 bytes vizinhos ao editar
    for (size_t i = 0; i + sc->step <= c->len; i += sc->step)
        for (int k = 0; k < sc->ntypes; k++) {
            int t = sc->types[k], sz = TYPES[B(t)].size, o = xoff(t);
            if (i % sz || i + (t & XOR && !o ? 2 * sz : sz) > c->len) continue;
            if (o && ((int64_t)i + o < 0 || i + o + 4 > c->len)) continue;
            const unsigned char *p = buf + i;
            uint32_t w[2];
            uint64_t q[2];
            unsigned char tg[8];
            if (t & XOR && sz == 8) {
                memcpy(q, p, 16);
                if (q[0] < 0x10000 || q[0] > ~0ULL - 0x10000) continue;  // chave de verdade é grande e sorteada
                q[0] ^= q[1];
                p = (const unsigned char *)q;
            } else if (t & XOR) {
                if (o) { memcpy(&w[0], p, 4); memcpy(&w[1], p + o, 4); }
                else { memcpy(w, p, 8); uint32_t kk = w[0]; w[0] = w[1]; w[1] = kk; }  // chave antes: w[1] = chave
                if (!xor_key((int32_t)w[1])) continue;
                w[0] ^= w[1];
                p = (const unsigned char *)w;
            } else if (t & TAG) {
                memcpy(tg, p, sz);
                if (untag(t, tg)) continue;
                p = tg;
            }
            if (matches_set(B(t), p, &sc->v[B(t)])) {
                hpush(&sc->out[ci], (hit_t){c->addr + i, bits(p, sz), (uint16_t)t, c->region});
                break;
            }
        }
}

// Busca desconhecida: a memória inteira vai para um arquivo e cada posição do tipo tem um bit de "ainda é candidata".
static chunks_t snap;
static uint8_t *smask;
static int snapfd = -1, snapt;
static size_t snapn;
static char snappath[64];

static void drop_snap(void) {
    if (snapfd >= 0) { close(snapfd); unlink(snappath); }
    snapfd = -1;
    free(snap.c);
    snap = (chunks_t){0};
    free(smask);
    smask = NULL;
    snapn = 0;
}

static size_t count_mask(void) {
    size_t n = 0, bytes = snap.total / TYPES[snapt].size / 8;
    for (size_t i = 0; i < bytes; i++) n += __builtin_popcount(smask[i]);
    return n;
}

static void snap_chunk(size_t ci, unsigned char *buf, void *ctx) {
    (void)ctx;
    const chunk_t *c = &snap.c[ci];
    int sz = TYPES[snapt].size;
    // ilegível agora: sai da busca
    if (read_mem(c->addr, buf, c->len) || pwrite(snapfd, buf, c->len, (off_t)c->off) != (ssize_t)c->len)
        memset(smask + c->off / sz / 8, 0, c->len / sz / 8);
}

static void cmd_snapshot(const char *name) {
    int t = find_type(name);
    if (t < 0 || (t & (XOR | TAG))) { printf("err tipo inválido: %s\n", name); return; }
    drop_snap();
    nres = nprev = 0;
    snap.want = ranges;
    if (each_region(add_chunks, &snap)) { puts("err não leu o mapa de memória"); return; }
    size_t bytes = snap.total / TYPES[t].size / 8;
    if (!bytes) { puts("err nenhuma região marcada para busca"); drop_snap(); return; }
    const char *dir = access("/data/local/tmp", W_OK) ? "/tmp" : "/data/local/tmp";
    struct statvfs fs;
    if (!statvfs(dir, &fs) && (uint64_t)fs.f_bavail * fs.f_frsize < snap.total + (64u << 20)) {
        printf("err sem espaço para a busca desconhecida: precisa de %" PRIu64 " MB livres em %s; desmarque regiões em Ajustes\n",
               (snap.total >> 20) + 64, dir);
        drop_snap();
        return;
    }
    snprintf(snappath, sizeof snappath, "%s/tinker-snap", dir);
    snapfd = open(snappath, O_RDWR | O_CREAT | O_TRUNC, 0600);
    smask = malloc(bytes);
    if (snapfd < 0 || !smask) { printf("err não criou %s: %s\n", snappath, strerror(errno)); drop_snap(); return; }
    snapt = t;
    memset(smask, 0xff, bytes);
    parallel(snap.n, snap_chunk, NULL);
    snapn = count_mask();
    printf("ok %zu\n", snapn);
}

// Faz a busca e deixa os resultados em res; devolve -1 (já com o erro impresso) se não deu.
static int run_search(const char *types, const char *value, scan_t *out_sc, int lenient) {
    scan_t sc = {.ntypes = 0};
    // vários tipos separados por vírgula (ex.: f32,f64); "auto" = Dword, Float, Qword e Double, como no GG
    char list[128], *save = NULL;
    snprintf(list, sizeof list, "%s", strcmp(types, "auto") ? types : "i32,f32,i64,f64");
    for (char *tok = strtok_r(list, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        int t = find_type(tok);
        if (t < 0 || sc.ntypes == 2 * NTYPES) { printf("err tipo inválido: %s\n", tok); return -1; }
        if (!parse_set_as(B(t), value, &sc.v[B(t)], lenient)) continue;  // ex.: 1.5 não entra como Dword
        // menores primeiro: no mesmo endereço ganha o tipo menor, que não invade bytes vizinhos
        int k = sc.ntypes++;
        while (k > 0 && TYPES[B(sc.types[k - 1])].size > TYPES[B(t)].size) { sc.types[k] = sc.types[k - 1]; k--; }
        sc.types[k] = t;
    }
    if (!sc.ntypes) { printf("err valor inválido: %s\n", value); return -1; }
    sc.step = 8;
    for (int k = 0; k < sc.ntypes; k++) if (TYPES[B(sc.types[k])].size < sc.step) sc.step = TYPES[B(sc.types[k])].size;
    drop_snap();
    nres = 0;
    nprev = 0;  // nova busca: não há refino para desfazer
    sc.cs.want = ranges;
    if (each_region(add_chunks, &sc.cs)) { puts("err não leu o mapa de memória"); return -1; }
    sc.out = calloc(sc.cs.n ? sc.cs.n : 1, sizeof *sc.out);
    if (!sc.out) { puts("err sem memória"); exit(1); }
    parallel(sc.cs.n, scan_chunk, &sc);
    collect(sc.out, sc.cs.n);
    free(sc.cs.c);
    if (out_sc) *out_sc = sc;
    return 0;
}

static void cmd_search(const char *types, const char *value) {
    if (!run_search(types, value, NULL, 0)) printf("ok %zu\n", nres);
}

static int cmp_hit(const void *a, const void *b) {
    uint64_t x = ((const hit_t *)a)->addr, y = ((const hit_t *)b)->addr;
    return x < y ? -1 : x > y;
}

// Busca em grupo (como o 100;250;7::64 do GameGuardian): todos os valores precisam aparecer dentro de uma janela de
// "range" bytes; ficam só os resultados que fazem parte de algum grupo assim (ex.: ouro, gema e nível no mesmo objeto).
static void cmd_group(const char *types, const char *value, const char *range) {
    uint64_t win = strtoull(range, NULL, 0);
    if (!win || win > 0x10000) { puts("err distância inválida (1 a 65536 bytes)"); return; }
    scan_t sc;
    int nv = count_items(value);
    if (nv > MAXV) { printf("err valor inválido: %s\n", value); return; }
    if (run_search(types, value, &sc, 1)) return;
    // itens iguais (100;100: vida e vida máxima) contam como um valor que precisa aparecer duas vezes; item que nenhum tipo
    // entendeu não é exigido (senão o grupo nunca fecha e dá 0 calado)
    char items[MAXV][64], buf[256], *save = NULL;
    int canon[MAXV], need[MAXV] = {0}, parsed[MAXV] = {0}, k = 0;
    snprintf(buf, sizeof buf, "%s", value);
    for (char *tok = strtok_r(buf, ";", &save); tok && k < MAXV; tok = strtok_r(NULL, ";", &save), k++) snprintf(items[k], sizeof items[k], "%s", tok);
    for (int t = 0; t < sc.ntypes; t++) for (int j = 0; j < sc.v[B(sc.types[t])].n; j++) parsed[sc.v[B(sc.types[t])].idx[j]] = 1;
    nv = 0;
    int total = 0;
    for (int j = 0; j < k; j++) {
        canon[j] = j;
        for (int i = 0; i < j; i++) if (!strcmp(items[i], items[j])) { canon[j] = i; break; }
        if (!parsed[j]) continue;
        if (need[canon[j]]++ == 0) nv++;
        total++;
    }
    if (total < 2) { printf("ok %zu\n", nres); return; }
    qsort(res, nres, sizeof *res, cmp_hit);
    // qual dos valores cada resultado é (o primeiro que casar)
    uint8_t *which = malloc(nres + 1);
    int *mark = calloc(nres + 1, sizeof *mark);
    if (!which || !mark) { puts("err sem memória"); exit(1); }
    for (size_t i = 0; i < nres; i++) {
        const vset_t *v = &sc.v[B(res[i].type)];
        which[i] = 0xff;
        for (int j = 0; j < v->n; j++)
            if (matches(B(res[i].type), (const unsigned char *)&res[i].old, &v->v[j])) { which[i] = (uint8_t)canon[v->idx[j]]; break; }
    }
    // janela deslizante: para cada começo l vai até onde cabe; com todos os valores dentro, marca [l, r) por diferenças
    int cnt[MAXV] = {0}, have = 0;
    size_t r = 0;
    for (size_t l = 0; l < nres; l++) {
        while (r < nres && res[r].addr - res[l].addr <= win) { if (which[r] < MAXV && ++cnt[which[r]] == need[which[r]]) have++; r++; }
        if (have == nv) { mark[l]++; mark[r]--; }
        if (which[l] < MAXV && cnt[which[l]]-- == need[which[l]]) have--;
    }
    size_t n = 0;
    int on = 0;
    for (size_t i = 0; i < nres; i++) { on += mark[i]; if (on > 0) res[n++] = res[i]; }
    nres = n;
    free(which);
    free(mark);
    printf("ok %zu\n", nres);
}

// Refino: igual a um valor, ou comparado com o valor da última busca/refino (busca desconhecida).
enum { OP_EQ, OP_INC, OP_DEC, OP_SAME, OP_CHANGED, OP_INCBY, OP_DECBY };
static const char *OPS[] = {"eq", "inc", "dec", "same", "changed", "incby", "decby"};

static double num(int t, const unsigned char *p) {
    if (t == T_F32) { float x; memcpy(&x, p, 4); return x; }
    if (t == T_F64) { double x; memcpy(&x, p, 8); return x; }
    int sz = TYPES[t].size;
    uint64_t x = bits(p, sz);
    if (sz < 8 && (x >> (sz * 8 - 1))) x |= ~0ULL << (sz * 8);
    return (double)(int64_t)x;
}

static int keep(int t, const unsigned char *cur, const unsigned char *old, int op, const vset_t *v) {
    int sz = TYPES[t].size;
    if (op == OP_EQ) return matches_set(t, cur, v);
    if (op == OP_SAME) return !memcmp(cur, old, sz);
    if (op == OP_CHANGED) return memcmp(cur, old, sz) != 0;
    double c = num(t, cur), o = num(t, old);
    if (op == OP_INC) return c > o;
    if (op == OP_DEC) return c < o;
    double d = op == OP_INCBY ? c - o : o - c;
    for (int i = 0; i < v->n; i++) {
        const value_t *x = &v->v[i];
        // float guardado nunca é exato: +0.1 vira +0.100000001
        double slack = TYPES[t].isfloat ? 1e-4 * fmax(1, x->range ? fmax(fabs(x->lo), fabs(x->hi)) : fabs(x->f)) : 0;
        if (x->range ? d >= x->lo - slack && d <= x->hi + slack : fabs(d - x->f) <= slack) return 1;
    }
    return 0;
}

typedef struct { int op; const vset_t *v; } cmpctx_t;

static void cmp_chunk(size_t ci, unsigned char *buf, void *ctx) {
    const cmpctx_t *cc = ctx;
    const chunk_t *c = &snap.c[ci];
    int sz = TYPES[snapt].size;
    uint8_t *m = smask + c->off / sz / 8;
    size_t nb = c->len / sz / 8, any = 0;
    for (size_t j = 0; j < nb && !any; j++) any = m[j];
    if (!any) return;  // pedaço já descartado: não lê nada
    unsigned char *cur = buf, *old = buf + CHUNK;
    if (read_mem(c->addr, cur, c->len) || pread(snapfd, old, c->len, (off_t)c->off) != (ssize_t)c->len) { memset(m, 0, nb); return; }
    for (size_t k = 0; k < c->len / sz; k++)
        if ((m[k >> 3] & (1 << (k & 7))) && !keep(snapt, cur + k * sz, old + k * sz, cc->op, cc->v)) m[k >> 3] &= ~(1 << (k & 7));
    if (pwrite(snapfd, cur, c->len, (off_t)c->off) != (ssize_t)c->len) memset(m, 0, nb);  // próxima comparação parte daqui
}

// Poucos candidatos: sai do arquivo e vira uma lista normal (aparece na tela, edita, desfaz).
static void snap_to_list(void) {
    int sz = TYPES[snapt].size;
    unsigned char *buf = malloc(CHUNK);
    if (!buf) { puts("err sem memória"); exit(1); }
    nres = 0;
    for (size_t ci = 0; ci < snap.n; ci++) {
        const chunk_t *c = &snap.c[ci];
        const uint8_t *m = smask + c->off / sz / 8;
        int loaded = 0;
        for (size_t k = 0; k < c->len / sz; k++) {
            if (!(m[k >> 3] & (1 << (k & 7)))) continue;
            if (!loaded && pread(snapfd, buf, c->len, (off_t)c->off) != (ssize_t)c->len) break;
            loaded = 1;
            push((hit_t){c->addr + k * sz, bits(buf + k * sz, sz), (uint16_t)snapt, c->region});
        }
    }
    free(buf);
    drop_snap();
}

static void cmd_compare(const char *opname, const char *value) {
    int op = -1;
    for (int i = 0; i < (int)(sizeof OPS / sizeof *OPS); i++) if (!strcmp(opname, OPS[i])) op = i;
    if (op < 0) { printf("err comparação inválida: %s\n", opname); return; }
    int needv = op == OP_EQ || op == OP_INCBY || op == OP_DECBY;
    vset_t v[NTYPES];
    int ok[NTYPES];
    for (int t = 0; t < NTYPES; t++) ok[t] = !needv || parse_set(t, value, &v[t]);
    if (snapfd >= 0) {
        if (!ok[snapt]) { printf("err valor inválido: %s\n", value); return; }
        cmpctx_t cc = {op, &v[snapt]};
        parallel(snap.n, cmp_chunk, &cc);
        snapn = count_mask();
        if (snapn <= list_max) snap_to_list();
        printf("ok %zu\n", snapfd >= 0 ? snapn : nres);
        return;
    }
    if (nres > capprev) {
        capprev = nres;
        prev = realloc(prev, capprev * sizeof *prev);
        if (!prev) { puts("err sem memória"); exit(1); }
    }
    memcpy(prev, res, nres * sizeof *res);
    nprev = nres;
    size_t k = 0;
    unsigned char cur[8], old[8];
    cbase = 1;  // a memória mudou desde a última leitura
    for (size_t i = 0; i < nres; i++) {
        int t = res[i].type, b = B(t), sz = TYPES[b].size;
        memcpy(old, &res[i].old, 8);
        if (ok[b] && !load(res[i].addr, t, cur, 1) && keep(b, cur, old, op, &v[b])) {
            res[k] = res[i];
            res[k++].old = bits(cur, sz);
        }
    }
    nres = k;
    printf("ok %zu\n", nres);
}

static void cmd_list(size_t n) {
    if (snapfd >= 0) { printf("ok %zu\n", snapn); return; }  // milhões ainda no arquivo: nada para mostrar
    unsigned char b[8];
    char out[64];
    for (size_t i = 0; i < nres && i < n; i++) {
        int t = res[i].type;
        if (load(res[i].addr, t, b, 0)) strcpy(out, "?");
        else format(B(t), b, out);
        printf("%" PRIx64 " %s %s %s\n", res[i].addr, tname(t), REGIONS[res[i].region], out);
    }
    printf("ok %zu\n", nres);
}

static void cmd_read(const char *addr, const char *name) {
    int t = find_type(name);
    unsigned char b[8];
    char out[64];
    if (t < 0) { printf("err tipo inválido: %s\n", name); return; }
    if (load(strtoull(addr, NULL, 16), t, b, 0)) { printf("err não leu %s\n", addr); return; }
    format(B(t), b, out);
    printf("ok %s\n", out);
}

// Lê COUNT valores seguidos a partir de ADDR: o visualizador de memória ("endereços próximos").
static void cmd_dump(const char *addr, const char *name, const char *count) {
    int t = find_type(name);
    if (t < 0 || (t & (XOR | TAG))) { printf("err tipo inválido: %s\n", name); return; }
    size_t n = strtoul(count, NULL, 10), sz = TYPES[t].size;
    if (n < 1 || n > 20001) n = 64;  // até 10000 linhas para cada lado na busca perto
    uint64_t a = strtoull(addr, NULL, 16);
    unsigned char *buf = malloc(n * sz);
    int whole = !read_mem(a, buf, n * sz);  // falha se cruzar uma página sem mapa; aí lê um por um
    char out[64];
    for (size_t i = 0; i < n; i++) {
        unsigned char *p = buf + i * sz;
        if (whole || !read_mem(a + i * sz, p, sz)) format(t, p, out);
        else strcpy(out, "?");
        printf("%" PRIx64 " %s\n", a + i * sz, out);
    }
    free(buf);
    puts("ok");
}

// Por que o valor não serve para escrever nesse tipo, numa frase que o app mostra como está.
static void bad_value(int t, const char *value) {
    static const char *NAMES[] = {"Byte", "Word", "Dword", "Qword", "Float", "Double"};
    char *end;
    double d = strtod(value, &end);
    int bits = TYPES[t].size * 8;
    if (end != value && !*end && d == floor(d) && !TYPES[t].isfloat && bits < 64)
        printf("err valor fora do tipo: %s não cabe em %s (de %.0f a %.0f); use um tipo maior (Qword)\n", value, NAMES[t], -ldexp(1, bits - 1), ldexp(1, bits) - 1);
    else printf("err valor inválido: %s\n", value);
}

static void cmd_write(const char *addr, const char *name, const char *value) {
    int t = find_type(name);
    value_t v;
    unsigned char b[8];
    if (t < 0) { printf("err tipo inválido: %s\n", name); return; }
    if (!parse_value(B(t), value, &v) || v.range) { bad_value(B(t), value); return; }
    encode(B(t), &v, b);
    if (store(strtoull(addr, NULL, 16), t, b)) printf("err falhou ao escrever: %s\n", strerror(errno));
    else puts("ok");
}

// Congelar dentro do daemon: uma thread reescreve a cada 100 ms, e só o que mudou. Antes o app mandava um "write"
// por salvo a cada 200 ms pelo pipe; com muitos salvos isso dava lag e atrasava os outros comandos.
#define FREEZE_MAX 512
#define FREEZE_FAILS 25  // 2,5 s sem conseguir escrever: o objeto foi liberado; sai da lista (escrever ali derruba o jogo)
typedef struct { uint64_t addr; int type, fails; unsigned char val[8]; } frz_t;
static frz_t frz[FREEZE_MAX];
static int nfrz;
static pthread_mutex_t frz_lock = PTHREAD_MUTEX_INITIALIZER;

static void *freeze_thread(void *arg) {
    (void)arg;
    for (;;) {
        usleep(100000);
        pthread_mutex_lock(&frz_lock);
        int k = 0;
        for (int i = 0; i < nfrz; i++) {
            frz_t *f = &frz[i];
            unsigned char cur[8];
            int len = TYPES[B(f->type)].size;
            // igual ao congelado: não escreve (menos mexida na memória do jogo); cifrado sempre reescreve (a chave muda)
            if (!(f->type & (XOR | TAG)) && !read_mem(f->addr, cur, len) && !memcmp(cur, f->val, len)) f->fails = 0;
            else if (store(f->addr, f->type, f->val)) f->fails++;
            else f->fails = 0;
            if (f->fails < FREEZE_FAILS) frz[k++] = *f;
        }
        nfrz = k;
        pthread_mutex_unlock(&frz_lock);
    }
    return NULL;
}

static void cmd_freeze(const char *addr, const char *name, const char *value) {
    int t = find_type(name);
    value_t v;
    unsigned char b[8] = {0};
    if (t < 0) { printf("err tipo inválido: %s\n", name); return; }
    if (!parse_value(B(t), value, &v) || v.range) { bad_value(B(t), value); return; }
    encode(B(t), &v, b);
    uint64_t a = strtoull(addr, NULL, 16);
    // escreve uma vez já: endereço que não grava é recusado aqui, não fica na lista
    if (store(a, t, b)) { printf("err falhou ao escrever: %s\n", strerror(errno)); return; }
    pthread_mutex_lock(&frz_lock);
    int i = 0;
    while (i < nfrz && !(frz[i].addr == a && frz[i].type == t)) i++;
    if (i == FREEZE_MAX) { pthread_mutex_unlock(&frz_lock); puts("err congelados demais"); return; }
    if (i == nfrz) nfrz++;
    frz[i] = (frz_t){a, t, 0, {0}};
    memcpy(frz[i].val, b, sizeof b);
    pthread_mutex_unlock(&frz_lock);
    puts("ok");
}

// "unfreeze all" ou "unfreeze ADDR TIPO"
static void cmd_unfreeze(const char *addr, const char *name) {
    int all = !strcmp(addr, "all"), t = all ? 0 : find_type(name);
    uint64_t a = all ? 0 : strtoull(addr, NULL, 16);
    pthread_mutex_lock(&frz_lock);
    int k = 0;
    for (int i = 0; i < nfrz; i++) if (!all && !(frz[i].addr == a && frz[i].type == t)) frz[k++] = frz[i];
    nfrz = k;
    pthread_mutex_unlock(&frz_lock);
    puts("ok");
}

// lista o que está congelado agora (o que saiu por falha não aparece: o app avisa)
static void cmd_frozen(void) {
    pthread_mutex_lock(&frz_lock);
    for (int i = 0; i < nfrz; i++) printf("%" PRIx64 " %s %d\n", frz[i].addr, tname(frz[i].type), frz[i].fails);
    printf("ok %d\n", nfrz);
    pthread_mutex_unlock(&frz_lock);
}

static void cmd_writeall(const char *value) {
    if (snapfd >= 0) { puts("err refine mais antes de editar todos"); return; }
    size_t fail = 0, skip = 0;
    for (size_t i = 0; i < nres; i++) {
        int t = res[i].type;
        value_t v;
        unsigned char b[8];
        if (!parse_value(B(t), value, &v) || v.range) { skip++; continue; }  // ex.: "1.5" não vai para Dword
        encode(B(t), &v, b);
        fail += store(res[i].addr, t, b) != 0;
    }
    printf("ok %zu %zu\n", nres - fail - skip, fail + skip);
}

// Ponteiros, como no Cheat Engine: um caminho "módulo+deslocamento → +o1 → +o2" que acha o valor
// mesmo depois de o jogo reiniciar, porque a base é um endereço fixo dentro de uma .so do jogo.
typedef struct { char name[96]; uint64_t base; int system; } module_t;
typedef struct { uint64_t start, end; int mod; } srange_t;  // parte gravável de um módulo (.data/.bss)
static module_t *mods;
static size_t nmods, nsr;
static srange_t *sr;
static pid_t mods_pid;
static int psize = 8;
#define TAGMASK 0x00ffffffffffffffULL  // Android arm64 marca ponteiros do malloc no byte de cima

static int ptr_size(void) {
    char path[64];
    unsigned char e[5];
    snprintf(path, sizeof path, "/proc/%d/exe", target);
    FILE *f = fopen(path, "rb");
    size_t n = f ? fread(e, 1, 5, f) : 0;
    if (f) fclose(f);
    return n == 5 && e[4] == 1 ? 4 : 8;  // ELFCLASS32: jogo 32 bits (app_process32)
}

static int read_ptr(uint64_t a, uint64_t *v) {
    *v = 0;
    if (read_mem(a, v, psize)) return -1;
    if (psize == 8) *v &= TAGMASK;
    return 0;
}

// Módulo = cada mapeamento de arquivo que começa com o cabeçalho ELF, mais os mapeamentos seguintes do mesmo
// arquivo e o .bss logo depois. Lib dentro do APK (sem extrair) vira "base.apk@deslocamento".
static int load_modules(void) {
    char path[64], line[1024], curpath[512] = "";
    snprintf(path, sizeof path, "/proc/%d/maps", target);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    nmods = nsr = 0;
    int cur = -1;
    uint64_t prev_end = 0;
    size_t capm = 0, caps = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long long s, e, off;
        char perms[8];
        int pos = 0;
        if (sscanf(line, "%llx-%llx %7s %llx %*s %*s %n", &s, &e, perms, &off, &pos) < 4) continue;
        char *name = pos ? line + pos : line + strlen(line);
        name[strcspn(name, "\n")] = 0;
        unsigned char magic[4];
        if (name[0] == '/' && perms[0] == 'r' && !read_mem(s, magic, 4) && !memcmp(magic, "\177ELF", 4)) {
            if (nmods == capm) {
                capm = capm ? capm * 2 : 256;
                mods = realloc(mods, capm * sizeof *mods);
                if (!mods) { puts("err sem memória"); exit(1); }
            }
            module_t *m = &mods[nmods];
            const char *b = strrchr(name, '/') + 1;
            size_t L = strlen(b);
            if (L > 4 && !strcmp(b + L - 4, ".apk")) snprintf(m->name, sizeof m->name, "%.70s@%llx", b, off);
            else snprintf(m->name, sizeof m->name, "%.95s", b);
            m->base = s;
            m->system = strncmp(name, "/data/", 6) != 0;
            cur = (int)nmods++;
            snprintf(curpath, sizeof curpath, "%.511s", name);
        } else if (cur >= 0 && strcmp(name, curpath) && !((!name[0] || !strcmp(name, "[anon:.bss]")) && s == prev_end)) {
            cur = -1;
        }
        prev_end = e;
        if (cur >= 0 && perms[1] == 'w') {
            if (nsr == caps) {
                caps = caps ? caps * 2 : 256;
                sr = realloc(sr, caps * sizeof *sr);
                if (!sr) { puts("err sem memória"); exit(1); }
            }
            sr[nsr++] = (srange_t){s, e, cur};
        }
    }
    fclose(f);
    mods_pid = target;
    return 0;
}

static int static_of(uint64_t a) {
    for (size_t i = 0; i < nsr; i++) if (a >= sr[i].start && a < sr[i].end) return sr[i].mod;
    return -1;
}

// Nó da busca de ponteiros: em addr há um ponteiro que, somado a off, dá o endereço do nó parent.
typedef struct { uint64_t addr; uint32_t parent, off; } pnode_t;
typedef struct { uint64_t addr; uint32_t node; } tgt_t;
typedef struct { const tgt_t *t; size_t nt; uint64_t lo, hi, maxoff; chunks_t cs; hits_t *out; } pscan_t;
static pnode_t *nodes;
static size_t nnodes, capnodes;
#define MAXNODES 200000  // por nível; acima disso os caminhos explodem e ficam inúteis
#define MAXFOUND 5000
#define MAXSHOW 500

static uint32_t node_push(uint64_t addr, uint32_t parent, uint32_t off) {
    if (nnodes == capnodes) {
        capnodes = capnodes ? capnodes * 2 : 4096;
        nodes = realloc(nodes, capnodes * sizeof *nodes);
        if (!nodes) { puts("err sem memória"); exit(1); }
    }
    nodes[nnodes] = (pnode_t){addr, parent, off};
    return (uint32_t)nnodes++;
}

static size_t lower(const tgt_t *t, size_t n, uint64_t v) {
    size_t a = 0, b = n;
    while (a < b) { size_t m = a + (b - a) / 2; if (t[m].addr < v) a = m + 1; else b = m; }
    return a;
}

static void ptr_chunk(size_t ci, unsigned char *buf, void *ctx) {
    pscan_t *ps = ctx;
    const chunk_t *c = &ps->cs.c[ci];
    if (read_mem(c->addr, buf, c->len)) return;
    for (size_t i = 0; i + psize <= c->len; i += psize) {
        uint64_t v = 0;
        memcpy(&v, buf + i, psize);
        if (psize == 8) v &= TAGMASK;
        if (v < ps->lo || v > ps->hi) continue;
        size_t j = lower(ps->t, ps->nt, v);
        if (j < ps->nt && ps->t[j].addr - v <= ps->maxoff) hpush(&ps->out[ci], (hit_t){c->addr + i, v, 0, c->region});
    }
}

static int cmp_tgt(const void *a, const void *b) {
    uint64_t x = ((const tgt_t *)a)->addr, y = ((const tgt_t *)b)->addr;
    return x < y ? -1 : x > y;
}

typedef struct { uint32_t id; int key; } found_t;
static int cmp_found(const void *a, const void *b) { return ((const found_t *)a)->key - ((const found_t *)b)->key; }

// Ordem dos caminhos: libs do jogo antes das do sistema, menos níveis, e offsets menores (campos perto do começo
// do objeto costumam ser os estáveis).
static int rank(uint32_t id, int system, int level) {
    uint64_t sum = 0;
    for (uint32_t k = id; nodes[k].parent != UINT32_MAX; k = nodes[k].parent) sum += nodes[k].off;
    return system * 10000000 + level * 100000 + (int)(sum / 16 < 99999 ? sum / 16 : 99999);
}

// only_game: base só em libs do jogo (/data/...); as do sistema (libc, linker) dão caminhos que quebram
static void cmd_pointers(const char *addr, const char *maxoffs, const char *levels, int only_game) {
    uint64_t goal = strtoull(addr, NULL, 16), maxoff = strtoull(maxoffs, NULL, 0);
    int lv = atoi(levels);
    if (lv < 1 || lv > 7) lv = 4;
    if (maxoff < 8 || maxoff > 65536) maxoff = 4096;
    psize = ptr_size();
    if (load_modules()) { puts("err não leu o mapa de memória"); return; }
    chunks_t cs = {.want = ~((1u << R_V) | (1u << 7))};  // sem vídeo e sem pilha: pilha muda a cada instante
    if (each_region(add_chunks, &cs)) { puts("err não leu o mapa de memória"); return; }
    nnodes = 0;
    node_push(goal, UINT32_MAX, 0);
    found_t *found = malloc(MAXFOUND * sizeof *found);
    if (!found) { puts("err sem memória"); exit(1); }
    size_t nfound = 0, lstart = 0, lend = 1;
    int limited = 0;
    int gm = static_of(goal);
    if (gm >= 0 && !(only_game && mods[gm].system)) found[nfound++] = (found_t){0, 0};
    for (int L = 1; L <= lv && lstart < lend && nfound < MAXFOUND; L++) {
        size_t nt = lend - lstart;
        tgt_t *t = malloc(nt * sizeof *t);
        hits_t *out = calloc(cs.n ? cs.n : 1, sizeof *out);
        if (!t || !out) { puts("err sem memória"); exit(1); }
        for (size_t i = 0; i < nt; i++) t[i] = (tgt_t){nodes[lstart + i].addr, (uint32_t)(lstart + i)};
        qsort(t, nt, sizeof *t, cmp_tgt);
        pscan_t ps = {t, nt, t[0].addr > maxoff ? t[0].addr - maxoff : 0, t[nt - 1].addr, maxoff, cs, out};
        parallel(cs.n, ptr_chunk, &ps);
        size_t next = nnodes;
        for (size_t ci = 0; ci < cs.n; ci++) {
            for (size_t k = 0; k < out[ci].n; k++) {
                uint64_t p = out[ci].h[k].addr, v = out[ci].h[k].old;
                int m = static_of(p);
                for (size_t j = lower(t, nt, v); j < nt && t[j].addr - v <= maxoff; j++) {
                    if (nnodes - next >= MAXNODES) { limited = 1; break; }
                    uint32_t id = node_push(p, t[j].node, (uint32_t)(t[j].addr - v));
                    // caminhos pelas libs do jogo (/data) primeiro, depois os mais curtos
                    if (m >= 0 && !(only_game && mods[m].system) && nfound < MAXFOUND) found[nfound++] = (found_t){id, rank(id, mods[m].system, L)};
                }
            }
            free(out[ci].h);
        }
        free(out);
        free(t);
        lstart = next;
        lend = nnodes;
    }
    free(cs.c);
    qsort(found, nfound, sizeof *found, cmp_found);
    for (size_t i = 0; i < nfound && i < MAXSHOW; i++) {
        uint32_t id = found[i].id;
        const module_t *m = &mods[static_of(nodes[id].addr)];
        printf("%s+%" PRIx64, m->name, nodes[id].addr - m->base);
        for (uint32_t k = id; nodes[k].parent != UINT32_MAX; k = nodes[k].parent) printf(" %x", nodes[k].off);
        putchar('\n');
    }
    free(found);
    printf("ok %zu %d\n", nfound < MAXSHOW ? nfound : MAXSHOW, limited || nfound >= MAXFOUND);
}

// IL2CPP (Unity): nomes de classe e campos lidos da memória do próprio jogo, sem o global-metadata.dat (que muda
// a cada versão e costuma vir cifrado). Todo objeto começa com o ponteiro da classe; a classe tem o nome em +2p,
// o namespace em +3p e um vetor de FieldInfo {nome, tipo, classe dona, offset, token}. Igual do Unity 2018 em diante.
static int ident(uint64_t a, char *out, size_t max) {
    if (a < 0x10000) return 0;
    memset(out, 0, max);
    size_t got = max - 1;
    if (read_mem(a, out, got)) { got = 16; if (read_mem(a, out, got)) return 0; }  // string perto do fim da página
    size_t n = strnlen(out, got);
    if (n == got) return 0;  // sem fim: não é um nome
    for (size_t i = 0; i < n; i++) if (out[i] < 0x20 || out[i] > 0x7e) return 0;
    return 1;
}

typedef struct { uint64_t fields; int nfields; char name[64], ns[64]; } klass_t;

static int read_klass(uint64_t k, klass_t *out) {
    uint64_t pn, pns, f, parent, fname;
    char tmp[64];
    int fis = 3 * psize + 8;
    if (k < 0x10000 || read_ptr(k + 2 * psize, &pn) || read_ptr(k + 3 * psize, &pns)) return 0;
    if (!ident(pn, out->name, sizeof out->name) || !out->name[0] || !ident(pns, out->ns, sizeof out->ns)) return 0;
    // o campo "fields" muda de lugar entre versões: acha o ponteiro cujo FieldInfo diz que a dona é esta classe
    for (int off = 4 * psize; off < 0x200; off += psize) {
        if (read_ptr(k + off, &f) || f < 0x10000 || read_ptr(f + 2 * psize, &parent) || parent != k) continue;
        if (read_ptr(f, &fname) || !ident(fname, tmp, sizeof tmp) || !tmp[0]) continue;
        int n = 0;
        while (n < 1024 && !read_ptr(f + (uint64_t)n * fis + 2 * psize, &parent) && parent == k) n++;
        out->fields = f;
        out->nfields = n;
        return 1;
    }
    return 0;
}

static void cmd_il2cpp(const char *addr) {
    psize = ptr_size();
    uint64_t a = strtoull(addr, NULL, 16), base = a & ~(uint64_t)(psize - 1), kp;
    int fis = 3 * psize + 8;
    klass_t k;
    for (uint64_t back = 0; back <= 0x1000 && back <= base; back += psize) {
        uint64_t s = base - back;
        if (read_ptr(s, &kp) || kp < 0x10000 || !read_klass(kp, &k)) continue;
        // a classe precisa ter um campo no endereço pedido; senão é outro objeto antes dele
        int hit = 0;
        for (int i = 0; i < k.nfields && !hit; i++) {
            int32_t off;
            if (read_mem(k.fields + (uint64_t)i * fis + 3 * psize, &off, 4)) continue;
            hit = off >= 0 && (uint64_t)off <= a - s && a - s < (uint64_t)off + 8;
        }
        if (!hit) continue;
        printf("class %s%s%s %" PRIx64 "\n", k.ns, k.ns[0] ? "." : "", k.name, s);
        for (int i = 0; i < k.nfields; i++) {
            uint64_t f = k.fields + (uint64_t)i * fis, pn, pt;
            int32_t off;
            uint32_t bits = 0;
            char nm[64];
            if (read_ptr(f, &pn) || !ident(pn, nm, sizeof nm) || read_mem(f + 3 * psize, &off, 4)) continue;
            if (!read_ptr(f + psize, &pt) && pt >= 0x10000 && read_mem(pt + psize, &bits, 4)) bits = 0;
            if (bits & 0x10) continue;  // estático: fica fora do objeto
            printf("field %x %02x %s\n", (unsigned)off, (bits >> 16) & 0xff, nm);
        }
        puts("ok");
        return;
    }
    puts("err não achei a classe IL2CPP (o jogo não é Unity IL2CPP, ou o valor não está dentro de um objeto)");
}

// "libjogo.so+1a2b30 18 40": base da lib + 1a2b30, lê o ponteiro, soma 18, lê, soma 40 = endereço do valor.
static void cmd_resolve(char *chain) {
    char *save = NULL, *tok = strtok_r(chain, " \n", &save);
    char *plus = tok ? strrchr(tok, '+') : NULL;
    if (!plus) { puts("err ponteiro inválido"); return; }
    *plus = 0;
    int m = -1;
    for (int pass = 0; pass < 2 && m < 0; pass++) {
        // a lista de módulos fica guardada: só relê se o jogo reiniciou ou a lib ainda não estava carregada
        if (pass || mods_pid != target) { psize = ptr_size(); if (load_modules()) { puts("err não leu o mapa de memória"); return; } }
        for (size_t i = 0; i < nmods; i++) if (!strcmp(mods[i].name, tok)) { m = (int)i; break; }
    }
    if (m < 0) { printf("err módulo %s não carregado\n", tok); return; }
    uint64_t a = mods[m].base + strtoull(plus + 1, NULL, 16), v;
    for (tok = strtok_r(NULL, " \n", &save); tok; tok = strtok_r(NULL, " \n", &save)) {
        if (read_ptr(a, &v)) { printf("err ponteiro quebrado em %" PRIx64 "\n", a); return; }
        // ponteiro nulo (objeto ainda não criado, jogo carregando): somar o offset daria um endereço sem sentido como 0x4ac
        if (v < 0x10000) { printf("err ponteiro nulo em %" PRIx64 "\n", a); return; }
        a = v + strtoull(tok, NULL, 16);
    }
    printf("ok %" PRIx64 "\n", a);
}

// AOB: sequência de bytes com curingas, ex.: "15CD5B07??020000". Cada ?? aceita qualquer byte.
#define AOB_MAX 192
typedef struct { unsigned char b[AOB_MAX], m[AOB_MAX]; int n, first; chunks_t cs; hits_t *out; } aob_t;

static int parse_aob(const char *s, aob_t *p) {
    p->n = 0;
    for (; *s && *s != '\n'; s += 2) {
        if (p->n == AOB_MAX || !s[1]) return 0;
        if (s[0] == '?' && s[1] == '?') { p->m[p->n] = 0; p->b[p->n++] = 0; continue; }
        char h[3] = {s[0], s[1], 0}, *e;
        long v = strtol(h, &e, 16);
        if (*e) return 0;
        p->m[p->n] = 0xff;
        p->b[p->n++] = (unsigned char)v;
    }
    p->first = -1;
    for (int i = 0; i < p->n && p->first < 0; i++) if (p->m[i]) p->first = i;
    return p->first >= 0;  // só curingas acharia a memória inteira
}

static void aob_chunk(size_t ci, unsigned char *buf, void *ctx) {
    aob_t *p = ctx;
    const chunk_t *c = &p->cs.c[ci];
    if (read_mem(c->addr, buf, c->len)) return;
    // shortcut: padrão que cruza a borda de um pedaço de 1 MB não é achado; raro com até 192 bytes
    size_t n = (size_t)p->n;
    if (c->len < n) return;
    // âncora: a maior sequência de bytes fixos do padrão, achada com memmem (bem mais rápido que testar byte a byte);
    // o padrão inteiro, com os ??, só é conferido onde a âncora bate
    size_t anc = (size_t)p->first, alen = 1;
    for (size_t i = 0; i < n;) {
        if (!p->m[i]) { i++; continue; }
        size_t j = i;
        while (j < n && p->m[j]) j++;
        if (j - i > alen) { anc = i; alen = j - i; }
        i = j;
    }
    size_t last = c->len - (n - anc);  // última posição em que a âncora ainda deixa o padrão caber
    for (size_t i = anc; i <= last;) {
        unsigned char *q = memmem(buf + i, last + alen - i, p->b + anc, alen);
        if (!q) break;
        size_t pos = (size_t)(q - buf), start = pos - anc;
        int ok = 1;
        for (size_t k = 0; k < n && ok; k++) ok = (buf[start + k] & p->m[k]) == p->b[k];
        if (ok) hpush(&p->out[ci], (hit_t){c->addr + start, bits(buf + start, p->n < 4 ? p->n : 4), T_I32, c->region});
        i = pos + 1;
    }
}

// keep=1: os achados viram os resultados (busca AOB do usuário, como Dword); keep=0: só lista até 50 endereços
// (assinatura automática), sem mexer nos resultados. mode "code" procura no código (r-x) em vez dos dados.
static void cmd_aob(const char *pat, const char *mode, int keep) {
    aob_t *p = calloc(1, sizeof *p);
    if (!p) { puts("err sem memória"); exit(1); }
    if (!parse_aob(pat, p)) { printf("err padrão inválido: %s\n", pat); free(p); return; }
    region_exec = !strcmp(mode, "code");
    p->cs.want = keep && !region_exec ? ranges : ~(1u << R_V);
    int r = each_region(add_chunks, &p->cs);
    region_exec = 0;
    if (r) { puts("err não leu o mapa de memória"); free(p->cs.c); free(p); return; }
    p->out = calloc(p->cs.n ? p->cs.n : 1, sizeof *p->out);
    if (!p->out) { puts("err sem memória"); exit(1); }
    parallel(p->cs.n, aob_chunk, p);
    if (keep) {
        drop_snap();
        nprev = 0;
        collect(p->out, p->cs.n);
        printf("ok %zu\n", nres);
    } else {
        size_t total = 0;
        for (size_t ci = 0; ci < p->cs.n; ci++) {
            for (size_t k = 0; k < p->out[ci].n; k++, total++) if (total < 50) printf("%" PRIx64 "\n", p->out[ci].h[k].addr);
            free(p->out[ci].h);
        }
        free(p->out);
        printf("ok %zu\n", total);
    }
    free(p->cs.c);
    free(p);
}

// Objetos de uma classe IL2CPP pelo nome ("Game.Player"): acha o nome no metadata, a classe que aponta para ele e os
// objetos que apontam para a classe. Não depende de caminho de ponteiro: serve para trazer um salvo de volta quando o
// jogo reabre e os caminhos quebraram. Lista até 100 objetos (o app escolhe pelo valor do campo).
static void find_values(const tgt_t *t, size_t nt, hits_t **out, chunks_t *cs) {
    pscan_t ps = {t, nt, t[0].addr, t[nt - 1].addr, 0, *cs, NULL};
    ps.out = calloc(cs->n ? cs->n : 1, sizeof *ps.out);
    if (!ps.out) { puts("err sem memória"); exit(1); }
    parallel(cs->n, ptr_chunk, &ps);
    *out = ps.out;
}

static void cmd_il2objs(const char *full) {
    psize = ptr_size();
    const char *dot = strrchr(full, '.');
    char ns[128] = "", name[128];
    if (dot) snprintf(ns, sizeof ns, "%.*s", (int)(dot - full), full);
    snprintf(name, sizeof name, "%s", dot ? dot + 1 : full);
    size_t nl = strlen(name);
    if (!nl || nl > AOB_MAX - 2) { puts("err nome de classe inválido"); return; }
    // 1) o nome no metadata: \0Nome\0 (o \0 antes evita achar o fim de outro nome)
    aob_t *p = calloc(1, sizeof *p);
    if (!p) { puts("err sem memória"); exit(1); }
    p->n = (int)nl + 2;
    memset(p->m, 0xff, p->n);
    memcpy(p->b + 1, name, nl);
    p->first = 1;
    region_all = 1;
    p->cs.want = ~(1u << R_V);
    int r = each_region(add_chunks, &p->cs);
    region_all = 0;
    if (r) { puts("err não leu o mapa de memória"); free(p->cs.c); free(p); return; }
    p->out = calloc(p->cs.n ? p->cs.n : 1, sizeof *p->out);
    if (!p->out) { puts("err sem memória"); exit(1); }
    parallel(p->cs.n, aob_chunk, p);
    tgt_t strs[64];
    size_t ns_n = 0;
    for (size_t ci = 0; ci < p->cs.n; ci++) {
        for (size_t k = 0; k < p->out[ci].n && ns_n < 64; k++) strs[ns_n++] = (tgt_t){p->out[ci].h[k].addr + 1, 0};
        free(p->out[ci].h);
    }
    free(p->out);
    free(p->cs.c);
    free(p);
    if (!ns_n) { puts("err classe não encontrada (o jogo não é IL2CPP, ou ainda não carregou essa classe)"); return; }
    qsort(strs, ns_n, sizeof *strs, cmp_tgt);
    // 2) a classe: tem um ponteiro para o nome em +2p (e o namespace certo em +3p)
    chunks_t cs = {.want = ~((1u << R_V) | (1u << 7))};
    if (each_region(add_chunks, &cs)) { puts("err não leu o mapa de memória"); return; }
    hits_t *out;
    find_values(strs, ns_n, &out, &cs);
    tgt_t ks[16];
    size_t nk = 0;
    klass_t kl;
    for (size_t ci = 0; ci < cs.n; ci++) {
        for (size_t k = 0; k < out[ci].n; k++) {
            uint64_t cand = out[ci].h[k].addr - 2 * psize;
            // sem ponto no nome ("PlayerData"): vale em qualquer namespace
            if (nk < 16 && read_klass(cand, &kl) && (!dot || !strcmp(kl.ns, ns)) && !strcmp(kl.name, name)) ks[nk++] = (tgt_t){cand, 0};
        }
        free(out[ci].h);
    }
    free(out);
    if (!nk) { free(cs.c); puts("err classe não encontrada (o nome existe, mas não achei a estrutura da classe)"); return; }
    qsort(ks, nk, sizeof *ks, cmp_tgt);
    // 3) os objetos: começam com o ponteiro da classe
    find_values(ks, nk, &out, &cs);
    size_t shown = 0;
    for (size_t ci = 0; ci < cs.n; ci++) {
        for (size_t k = 0; k < out[ci].n; k++) {
            uint64_t o = out[ci].h[k].addr, pn;
            char tmp[64];
            // FieldInfo {nome, tipo, classe}: também aponta para a classe, mas tem um nome 2 ponteiros antes; não é objeto
            if (!read_ptr(o - 2 * psize, &pn) && ident(pn, tmp, sizeof tmp) && tmp[0]) continue;
            if (shown < 100) { printf("obj %" PRIx64 "\n", o); shown++; }
        }
        free(out[ci].h);
    }
    free(out);
    free(cs.c);
    printf("ok %zu\n", shown);
}

// Todas as classes IL2CPP do jogo (com campos): procura na memória estruturas de classe — imagem, nome e namespace
// (os dois no metadata, perto um do outro) e um vetor de campos cuja dona é ela. Lista "class ns.Nome endereço nCampos".
static void klass_chunk(size_t ci, unsigned char *buf, void *ctx) {
    pscan_t *ps = ctx;
    const chunk_t *c = &ps->cs.c[ci];
    if (read_mem(c->addr, buf, c->len)) return;
    klass_t k;
    size_t p = (size_t)psize;
    // 64 bits até 48 bits de endereço (Android arm64 usa 39; o teste no PC, x86-64, usa 0x55...); 32 bits: qualquer um acima de 64 KB
    uint64_t lo = p == 8 ? 0x10000000 : 0x10000, hi = p == 8 ? 0x1000000000000ULL : 0x100000000ULL;
    for (size_t i = 0; i + 6 * p <= c->len; i += p) {
        uint64_t img = 0, nm = 0, ns = 0;
        uint32_t bits;
        memcpy(&img, buf + i, p);
        memcpy(&nm, buf + i + 2 * p, p);
        memcpy(&ns, buf + i + 3 * p, p);
        if (p == 8) { img &= TAGMASK; nm &= TAGMASK; ns &= TAGMASK; }
        // filtro barato antes de ler a classe inteira: três ponteiros, nome e namespace perto um do outro
        if (img < lo || img >= hi || nm < lo || nm >= hi || ns < lo || ns >= hi) continue;
        if ((nm > ns ? nm - ns : ns - nm) > 0x4000000) continue;
        // logo depois vem o próprio tipo da classe (byval_arg: dado + bits); o código do tipo é classe, struct ou genérico
        memcpy(&bits, buf + i + 5 * p, 4);
        unsigned code = (bits >> 16) & 0xff;
        if (code != 0x11 && code != 0x12 && code != 0x15) continue;
        if (read_klass(c->addr + i, &k) && k.nfields > 0) hpush(&ps->out[ci], (hit_t){c->addr + i, (uint64_t)k.nfields, 0, c->region});
    }
}

static void cmd_il2classes(void) {
    psize = ptr_size();
    chunks_t cs = {.want = ~((1u << R_V) | (1u << 7))};
    if (each_region(add_chunks, &cs)) { puts("err não leu o mapa de memória"); return; }
    pscan_t ps = {NULL, 0, 0, 0, 0, cs, calloc(cs.n ? cs.n : 1, sizeof(hits_t))};
    if (!ps.out) { puts("err sem memória"); exit(1); }
    parallel(cs.n, klass_chunk, &ps);
    size_t shown = 0;
    klass_t k;
    for (size_t ci = 0; ci < cs.n; ci++) {
        for (size_t j = 0; j < ps.out[ci].n; j++)
            if (shown < 20000 && read_klass(ps.out[ci].h[j].addr, &k)) {
                // nomes dos campos (até 64, linha até ~1 KB): o filtro e a IA acham "gold" mesmo quando a classe se chama PlayerData
                char names[1100] = "", nm[64];
                size_t len = 0;
                int fis = 3 * psize + 8;
                uint64_t fname;
                for (int f = 0; f < k.nfields && f < 64; f++) {
                    if (read_ptr(k.fields + (uint64_t)f * fis, &fname) || !ident(fname, nm, sizeof nm) || !nm[0]) continue;
                    size_t l = strlen(nm);
                    if (len + l + 2 >= sizeof names) break;
                    if (len) names[len++] = ',';
                    memcpy(names + len, nm, l + 1);
                    len += l;
                }
                printf("class %s%s%s %" PRIx64 " %d %s\n", k.ns, k.ns[0] ? "." : "", k.name, ps.out[ci].h[j].addr, k.nfields, names);
                shown++;
            }
        free(ps.out[ci].h);
    }
    free(ps.out);
    free(cs.c);
    printf("ok %zu\n", shown);
}

// Roda um logcat (como root vê todos os apps) e repassa cada linha com "| ".
static void run_logcat(const char *args) {
    char cmd[256], line[1026];
    snprintf(cmd, sizeof cmd, "logcat -d %s 2>&1", args);
    FILE *p = popen(cmd, "r");
    if (!p) { printf("err logcat indisponível: %s\n", strerror(errno)); return; }
    while (fgets(line, sizeof line - 1, p)) {
        if (line[strlen(line) - 1] != '\n') strcat(line, "\n");
        printf("| %s", line);
    }
    pclose(p);
    puts("ok");
}

// Print da tela (para a IA ler os valores que o jogo mostra): PNG do screencap, em base64, 76 letras por linha com "| ".
// Vai pelo protocolo em vez de arquivo: um arquivo criado pelo root nem sempre pode ser lido pelo app com o SELinux ligado.
static size_t read_full(FILE *f, unsigned char *buf, size_t n) {
    size_t got = 0, r;
    while (got < n && (r = fread(buf + got, 1, n - got, f)) > 0) got += r;
    return got;
}

static void cmd_screencap(void) {
    static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    FILE *p = popen("screencap -p 2>/dev/null", "r");
    if (!p) { printf("err screencap indisponível: %s\n", strerror(errno)); return; }
    unsigned char in[57];
    char out[80];
    size_t n, total = 0;
    // blocos cheios de 57 bytes (76 letras): o "=" de enchimento só pode aparecer no último
    while ((n = read_full(p, in, sizeof in)) > 0) {
        size_t o = 0;
        for (size_t i = 0; i < n; i += 3) {
            unsigned v = in[i] << 16 | (i + 1 < n ? in[i + 1] << 8 : 0) | (i + 2 < n ? in[i + 2] : 0);
            out[o++] = b64[v >> 18 & 63];
            out[o++] = b64[v >> 12 & 63];
            out[o++] = i + 1 < n ? b64[v >> 6 & 63] : '=';
            out[o++] = i + 2 < n ? b64[v & 63] : '=';
        }
        out[o] = 0;
        printf("| %s\n", out);
        total += n;
        if (n < sizeof in) break;
    }
    pclose(p);
    if (!total) { puts("err o screencap não devolveu imagem"); return; }
    printf("ok %zu\n", total);
}

// Toques na tela para a IA controlar o jogo: só números passam (nada de texto vai para o shell).
static void cmd_input(const char *kind, char *const args[], int n) {
    int need = !strcmp(kind, "tap") ? 2 : !strcmp(kind, "swipe") ? 5 : !strcmp(kind, "keyevent") ? 1 : -1;
    if (need < 0 || n < need) { puts("err uso: input tap X Y | input swipe X1 Y1 X2 Y2 MS | input keyevent N"); return; }
    char nums[5][24];
    char *argv[8] = {"input", (char *)kind};
    for (int i = 0; i < need; i++) {
        char *end;
        long v = strtol(args[i], &end, 10);
        if (*end || end == args[i] || v < 0 || v > 100000) { printf("err número inválido: %s\n", args[i]); return; }
        snprintf(nums[i], sizeof nums[i], "%ld", v);
        argv[2 + i] = nums[i];
    }
    argv[2 + need] = NULL;
    // direto, sem shell no meio: um processo a menos por toque (a IA toca muito quando controla o jogo)
    int fds[2];
    if (pipe(fds)) { printf("err input indisponível: %s\n", strerror(errno)); return; }
    fflush(stdout);
    pid_t pid = fork();
    if (pid < 0) { close(fds[0]); close(fds[1]); printf("err input indisponível: %s\n", strerror(errno)); return; }
    if (pid == 0) {
        dup2(fds[1], 1); dup2(fds[1], 2); close(fds[0]); close(fds[1]);
        execvp("input", argv);
        _exit(127);
    }
    close(fds[1]);
    FILE *p = fdopen(fds[0], "r");
    char line[256];
    int said = 0;
    while (p && fgets(line, sizeof line, p)) { printf("| %s", line); said = 1; }
    if (p) fclose(p); else close(fds[0]);
    int st = 0;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    if (!WIFEXITED(st) || WEXITSTATUS(st) != 0) { printf("err o comando input falhou%s\n", said ? " (veja acima)" : ""); return; }
    puts("ok");
}

// App em primeiro plano (a janela com foco): antes de cada toque da IA, para nunca tocar fora do jogo (ex.: compra na Play Store).
static void cmd_focus(void) {
    FILE *p = popen("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", "r");
    if (!p) { printf("err dumpsys indisponível: %s\n", strerror(errno)); return; }
    char line[512] = "";
    if (!fgets(line, sizeof line, p)) line[0] = 0;
    pclose(p);
    line[strcspn(line, "\n")] = 0;
    if (!line[0]) { puts("err não deu para ver o app em primeiro plano"); return; }
    printf("| %s\nok\n", line);
}

static void cmd_ps(void) {
    DIR *d = opendir("/proc");
    struct dirent *e;
    while (d && (e = readdir(d))) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        char path[300], name[256] = "";
        snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        size_t n = fread(name, 1, sizeof name - 1, f);
        fclose(f);
        name[n] = 0;
        if (name[0]) printf("%s %s\n", e->d_name, name);
    }
    if (d) closedir(d);
    puts("ok");
}

// O jogo nunca pode ficar pausado (SIGSTOP) depois que o daemon sai: o app caiu, foi fechado ou matou o su.
static void resume_target(void) { if (target > 0) kill(target, SIGCONT); }

static void on_exit_signal(int sig) {
    (void)sig;
    resume_target();
    if (snapfd >= 0) unlink(snappath);
    _exit(0);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (getenv("TINKER_LIST_MAX")) list_max = strtoul(getenv("TINKER_LIST_MAX"), NULL, 10);
    signal(SIGPIPE, SIG_IGN);  // app morreu: escrever falha em vez de matar o daemon antes de continuar o jogo
    signal(SIGTERM, on_exit_signal);
    signal(SIGHUP, on_exit_signal);
    signal(SIGINT, on_exit_signal);
    char line[1024], cmd[32], a1[400], a2[128], a3[128], a4[128];
    pthread_t ft;
    if (!pthread_create(&ft, NULL, freeze_thread, NULL)) pthread_detach(ft);
    puts("ok tinkerd 4");
    while (fgets(line, sizeof line, stdin)) {
        a1[0] = a2[0] = a3[0] = a4[0] = 0;
        if (sscanf(line, "%31s %399s %127s %127s %127s", cmd, a1, a2, a3, a4) < 1) continue;
        if (!strcmp(cmd, "ps")) { cmd_ps(); continue; }
        if (!strcmp(cmd, "screencap")) { cmd_screencap(); continue; }
        if (!strcmp(cmd, "focus")) { cmd_focus(); continue; }
        if (!strcmp(cmd, "input")) {
            char x1[32] = "", x2[32] = "", x3[32] = "", x4[32] = "", x5[32] = "";
            char *const xs[] = {x1, x2, x3, x4, x5};
            char kind[16] = "";
            int n = sscanf(line, "%*s %15s %31s %31s %31s %31s %31s", kind, x1, x2, x3, x4, x5);
            cmd_input(kind, xs, n > 0 ? n - 1 : 0);
            continue;
        }
        if (!strcmp(cmd, "info")) {
            // para o relatório: confirma root, kernel, tamanho de página e SELinux
            struct utsname u;
            char se = '?';
            FILE *f = fopen("/sys/fs/selinux/enforce", "r");
            if (f) { se = (char)fgetc(f); fclose(f); }
            uname(&u);
            printf("ok uid=%d kernel=%s pagina=%ld selinux=%s pid=%d\n", getuid(), u.release, sysconf(_SC_PAGESIZE),
                   se == '1' ? "enforcing" : se == '0' ? "permissive" : "?", (int)getpid());
            continue;
        }
        // buffer de falhas: por que um app (o jogo ou o Tinker) caiu
        if (!strcmp(cmd, "crashlog")) { run_logcat("-b crash -t 200"); continue; }
        if (!strcmp(cmd, "logs")) {
            // funciona mesmo depois do jogo fechar: o buffer guarda as últimas linhas do PID
            char args[64];
            snprintf(args, sizeof args, target ? "-t 400 --pid=%d" : "-t 400", target);
            run_logcat(args);
            continue;
        }
        if (!strcmp(cmd, "attach")) {
            resume_target();  // trocou de jogo: o anterior não fica parado
            pthread_mutex_lock(&frz_lock);  // a thread do congelamento usa target e memfd
            nfrz = 0;
            target = atoi(a1);
            char path[64];
            snprintf(path, sizeof path, "/proc/%d/mem", target);
            if (memfd >= 0) close(memfd);
            memfd = open(path, O_RDWR);
            pthread_mutex_unlock(&frz_lock);
            nres = nprev = 0;
            drop_snap();
            if (!alive()) printf("err processo %d não existe\n", target);
            else puts("ok");
            continue;
        }
        if (!target) { puts("err nenhum jogo escolhido"); continue; }
        if (!alive()) { puts("err o jogo fechou"); continue; }
        if (!strcmp(cmd, "regions")) cmd_regions();
        else if (!strcmp(cmd, "ranges")) puts(set_ranges(a1) ? "ok" : "err região inválida");
        else if (!strcmp(cmd, "search")) cmd_search(a1, a2);
        else if (!strcmp(cmd, "group")) cmd_group(a1, a2, a3);
        else if (!strcmp(cmd, "refine")) cmd_compare("eq", a1);
        else if (!strcmp(cmd, "compare")) cmd_compare(a1, a2);
        else if (!strcmp(cmd, "snapshot")) cmd_snapshot(a1);
        else if (!strcmp(cmd, "pointers")) cmd_pointers(a1, a2, a3, a4[0] == '1');
        else if (!strcmp(cmd, "aob")) cmd_aob(a1, a2, 1);
        else if (!strcmp(cmd, "aobfind")) cmd_aob(a1, "data", 0);
        else if (!strcmp(cmd, "il2cpp")) cmd_il2cpp(a1);
        else if (!strcmp(cmd, "il2objs")) cmd_il2objs(a1);
        else if (!strcmp(cmd, "il2classes")) cmd_il2classes();
        else if (!strcmp(cmd, "resolve")) cmd_resolve(strstr(line, "resolve") + 7);
        else if (!strcmp(cmd, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(cmd, "read")) cmd_read(a1, a2);
        else if (!strcmp(cmd, "dump")) cmd_dump(a1, a2, a3);
        else if (!strcmp(cmd, "write")) cmd_write(a1, a2, a3);
        else if (!strcmp(cmd, "writeall")) cmd_writeall(a1);
        else if (!strcmp(cmd, "freeze")) cmd_freeze(a1, a2, a3);
        else if (!strcmp(cmd, "unfreeze")) cmd_unfreeze(a1, a2);
        else if (!strcmp(cmd, "frozen")) cmd_frozen();
        else if (!strcmp(cmd, "pause")) puts(kill(target, SIGSTOP) ? "err não pausou" : "ok");
        else if (!strcmp(cmd, "resume")) puts(kill(target, SIGCONT) ? "err não continuou" : "ok");
        else if (!strcmp(cmd, "kill")) puts(kill(target, SIGKILL) ? "err não fechou" : "ok");
        else if (!strcmp(cmd, "reset")) { nres = nprev = 0; drop_snap(); puts("ok 0"); }
        else if (!strcmp(cmd, "undo")) {
            // troca atual e anterior: desfazer de novo refaz
            if (!nprev) { puts("err nada para desfazer"); continue; }
            if (nprev > cap) { cap = nprev; res = realloc(res, cap * sizeof *res); if (!res) { puts("err sem memória"); exit(1); } }
            if (nres > capprev) { capprev = nres; prev = realloc(prev, capprev * sizeof *prev); if (!prev) { puts("err sem memória"); exit(1); } }
            hit_t *tmp = malloc((nres ? nres : 1) * sizeof *tmp);
            memcpy(tmp, res, nres * sizeof *res);
            memcpy(res, prev, nprev * sizeof *res);
            memcpy(prev, tmp, nres * sizeof *res);
            free(tmp);
            size_t t = nres; nres = nprev; nprev = t;
            printf("ok %zu\n", nres);
        }
        else printf("err comando desconhecido: %s\n", cmd);
    }
    drop_snap();  // não deixa o arquivo da busca desconhecida para trás
    resume_target();
    return 0;
}
