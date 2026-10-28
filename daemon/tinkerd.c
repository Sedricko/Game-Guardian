// tinkerd: daemon de memória do Tinker. Roda como root e fala por stdin/stdout,
// um comando por linha; cada resposta termina com "ok ..." ou "err ...".
// Linhas de texto livre (ex.: crashlog) começam com "| " para não confundir o protocolo.
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64  // pread em endereços acima de 2 GB mesmo num build 32 bits
#include <ctype.h>
#include <dirent.h>
#include <elf.h>
#include <stddef.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <time.h>
#if defined(__x86_64__)
#include <sys/user.h>
#elif defined(__aarch64__)
#include <asm/ptrace.h>
#endif
#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif
#ifndef NT_ARM_HW_BREAK
#define NT_ARM_HW_BREAK 0x402
#endif
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
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
// li / lf: número do Lua (xLua, ToLua): 8 bytes do valor e o byte do tipo logo depois (3 ou 0x13: inteiro e decimal,
// trocados entre o Lua 5.3 e o 5.4). A busca só aceita o número com essa marca; ler e escrever mexem só nos 8 bytes.
#define LUA 0x80
static int lua_tag(int x) { return x == 0x03 || x == 0x13; }
#define B(t) ((t) & 0x1f)
static int xoff(int t) { return (t >> 8) ? ((t >> 8) - 17) * 4 : 0; }  // 0 = o cifrado de sempre (chave antes)

static int find_type(const char *s) {
    if (!strcmp(s, "xi32")) return T_I32 | XOR;
    if (!strcmp(s, "xf32")) return T_F32 | XOR;
    if (!strcmp(s, "xi64")) return T_I64 | XOR;  // ObscuredLong/ObscuredDouble: chave de 8 bytes em addr, valor^chave em addr+8
    if (!strcmp(s, "xf64")) return T_F64 | XOR;
    if (!strcmp(s, "i32x2")) return T_I32 | TAG;
    if (!strcmp(s, "li")) return T_I64 | LUA;
    if (!strcmp(s, "lf")) return T_F64 | LUA;
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
    if (t & LUA) return B(t) == T_I64 ? "li" : "lf";
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
    // memória herdada do Zygote (o molde de todo app) e as imagens do sistema: igual em todos os apps, criada antes do
    // jogo existir; valor do jogo nunca fica nela, só deixava a busca mais lenta e com números falsos (o "Pular Zygote" do GG)
    if (strstr(name, "dalvik-zygote") || strstr(name, "/system/framework/") || strstr(name, "boot.art") || strstr(name, "boot-framework.art")) return -1;
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
static int exec_only;    // com region_exec: 0 todo o código; 1 só o do jogo (Xa, /data); 2 só o do sistema (Xs, /system /apex /vendor)
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
        if (region_exec && exec_only) {
            int game = !strncmp(name, "/data/", 6);
            int sys = !strncmp(name, "/system/", 8) || !strncmp(name, "/apex/", 6) || !strncmp(name, "/vendor/", 8) || !strncmp(name, "/product/", 9);
            if (exec_only == 1 ? !game : !sys) continue;
        }
        int r = region_of(perms, name);
        if (r >= 0) fn(s, e, r, ctx);
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

// v cai dentro de algum pedaço (estão em ordem de endereço): é um ponteiro para a memória do jogo
static int in_chunks(const chunks_t *cs, uint64_t v) {
    size_t lo = 0, hi = cs->n;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (v < cs->c[mid].addr) hi = mid;
        else if (v >= cs->c[mid].addr + cs->c[mid].len) lo = mid + 1;
        else return 1;
    }
    return 0;
}

// os 8 bytes alinhados em volta de "at" são um ponteiro? Cifrado de verdade tem chave sorteada, não metade de um ponteiro
// (25/10, Last Garden: o 🔒 achava "valores" dentro da memória do Lua; congelar escreveu em ponteiros e o jogo caiu)
static int ptr_at(const unsigned char *b, size_t len, size_t at, const chunks_t *cs) {
    size_t q = at & ~(size_t)7;
    if (q + 8 > len) return 0;
    uint64_t v;
    memcpy(&v, b + q, 8);
    v &= 0x00ffffffffffffffULL;  // Android arm64 marca ponteiros do malloc no byte de cima
    return v >= 0x10000 && in_chunks(cs, v);
}

// Pedaço que o jogo nunca usou (nenhuma página de verdade no pagemap; a página zero que a própria leitura mapeia não conta).
// No emulador com tradução arm64 o Orecraft (Unity) reserva ~15 GB anônimos quase vazios: cada busca lia tudo (6 min) e a
// foto passava do limite. Só a memória anônima (Jh, J, Ch, Ca, A, S): a de arquivo não lida ainda guarda o conteúdo do arquivo.
// Sem pagemap lê tudo como antes.
// shortcut: a página zero gigante (THP "always") conta como usada; ler o pfn dela também se um aparelho precisar.
static int pm_fd = -1;
static pid_t pm_pid;
static uint64_t zero_pfn;
static int chunk_unused(uint64_t a, uint32_t len, int region) {
    if (region != 0 && region != 1 && region != 2 && region != 3 && region != 6 && region != 7) return 0;
    long pg = sysconf(_SC_PAGESIZE);
    if (pm_pid != target) {
        if (pm_fd >= 0) close(pm_fd);
        char path[64];
        snprintf(path, sizeof path, "/proc/%d/pagemap", target);
        pm_fd = open(path, O_RDONLY);
        pm_pid = target;
        if (!zero_pfn) {  // o pfn da página zero: uma página nossa só lida, nunca escrita (0 = sem root, o kernel esconde o pfn)
            volatile char *z = mmap(NULL, pg, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            int self = open("/proc/self/pagemap", O_RDONLY);
            uint64_t e = 0;
            if (z != MAP_FAILED && self >= 0) {
                (void)z[0];
                if (pread(self, &e, 8, (uint64_t)(uintptr_t)z / pg * 8) == 8 && (e >> 63)) zero_pfn = e & ((1ULL << 55) - 1);
            }
            if (self >= 0) close(self);
            if (z != MAP_FAILED) munmap((void *)z, pg);
        }
    }
    if (pm_fd < 0) return 0;
    uint64_t e[CHUNK / 4096];
    size_t n = (len + pg - 1) / pg;
    if (n > sizeof e / 8 || pread(pm_fd, e, n * 8, a / pg * 8) != (ssize_t)(n * 8)) return 0;
    for (size_t i = 0; i < n; i++) {
        if (e[i] >> 62 & 1) return 0;  // na troca (zram): tem dado
        if (e[i] >> 63 && (!zero_pfn || (e[i] & ((1ULL << 55) - 1)) != zero_pfn)) return 0;
    }
    return 1;
}

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
        if (chunk_unused(a, len, region)) continue;
        cs->c[cs->n++] = (chunk_t){a, cs->total, len, (uint8_t)region};
        cs->total += len;
    }
}

// Roda fn em cada pedaço usando todos os núcleos (até 8). Cada thread tem um buffer de 2 MB.
typedef void (*chunk_fn)(size_t i, unsigned char *buf, void *ctx);
typedef struct { chunk_fn fn; void *ctx; size_t n, next; pthread_t main; } pool_t;

static void *worker(void *arg) {
    pool_t *p = arg;
    // threads extras da varredura com prioridade baixa: a tela do Tinker e o jogo continuam fluindo (no emulador a
    // busca usava todos os núcleos e a tela parava 1–2 s); a thread principal fica normal, a busca não demora mais quando sobra CPU
    if (!pthread_equal(pthread_self(), p->main)) setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), 10);
    unsigned char *buf = malloc(2 * CHUNK);
    if (!buf) return NULL;  // as outras threads fazem o trabalho
    for (size_t i; (i = __atomic_fetch_add(&p->next, 1, __ATOMIC_RELAXED)) < p->n;) p->fn(i, buf, p->ctx);
    free(buf);
    return NULL;
}

static void parallel(size_t n, chunk_fn fn, void *ctx) {
    pool_t p = {fn, ctx, n, 0, pthread_self()};
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
    if (t & LUA) {
        unsigned char p[9];
        if (cached ? cread(a, p, 9) : read_mem(a, p, 9)) return -1;
        if (!lua_tag(p[8])) return -1;  // o lugar deixou de ser um número do Lua
        memcpy(out, p, 8);
        return 0;
    }
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
    if (t & LUA) return write_mem(a, val, 8);
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

// regiões padrão do jogo ("rangesdefault"): num Unity grande no emulador J/Jh/O somam 15 GB e cada busca levava minutos;
// os valores do jogo ficam em Ca/Cb/A. "ranges all" volta a este padrão
static unsigned ranges_default = ~(1u << R_V);

static int set_ranges(const char *list) {
    if (!strcmp(list, "all")) { ranges = ranges_default; return 1; }
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
            if (i % sz || i + (t & LUA ? 9 : t & XOR && !o ? 2 * sz : sz) > c->len) continue;
            if (t & LUA && !lua_tag(buf[i + 8])) continue;
            if (o && ((int64_t)i + o < 0 || i + o + 4 > c->len)) continue;
            const unsigned char *p = buf + i;
            uint32_t w[2];
            uint64_t q[2];
            unsigned char tg[8];
            if (t & XOR && sz == 8) {
                memcpy(q, p, 16);
                if (q[0] < 0x10000 || q[0] > ~0ULL - 0x10000) continue;  // chave de verdade é grande e sorteada
                if (ptr_at(buf, c->len, i, &sc->cs)) continue;
                q[0] ^= q[1];
                p = (const unsigned char *)q;
            } else if (t & XOR) {
                if (o) { memcpy(&w[0], p, 4); memcpy(&w[1], p + o, 4); }
                else { memcpy(w, p, 8); uint32_t kk = w[0]; w[0] = w[1]; w[1] = kk; }  // chave antes: w[1] = chave
                if (!xor_key((int32_t)w[1])) continue;
                if (ptr_at(buf, c->len, i, &sc->cs) || ptr_at(buf, c->len, i + (o ? o : 4), &sc->cs)) continue;
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
    if (t < 0 || (t & (XOR | TAG | LUA))) { printf("err tipo inválido: %s\n", name); return; }
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
// lin "A;B": o número da tela foi de A para B e a memória guarda k×tela+c (k inteiro de 1 a 100000, c qualquer: dobro,
// ×10, +100); xk "A;B": guarda tela XOR uma chave fixa (a mesma nas duas vezes). Os dois acham o "guardado diferente".
enum { OP_EQ, OP_INC, OP_DEC, OP_SAME, OP_CHANGED, OP_INCBY, OP_DECBY, OP_LIN, OP_XK };
static const char *OPS[] = {"eq", "inc", "dec", "same", "changed", "incby", "decby", "lin", "xk"};

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
    if (op == OP_LIN || op == OP_XK) {
        if (v->n != 2 || TYPES[t].isfloat) return 0;
        int64_t a = llround(v->v[0].f), b = llround(v->v[1].f), x1 = (int64_t)o, x2 = (int64_t)c;
        if (a == b || x1 == x2) return 0;
        if (op == OP_XK) {
            uint64_t m = sz == 8 ? ~0ULL : (1ULL << (sz * 8)) - 1, k1 = ((uint64_t)x1 ^ (uint64_t)a) & m, k2 = ((uint64_t)x2 ^ (uint64_t)b) & m;
            return k1 == k2 && k1 != 0;
        }
        int64_t d = x2 - x1, dv = b - a;
        if (d % dv) return 0;
        // só os multiplicadores que jogos usam: com o número da tela subindo de 1, "qualquer k" deixava passar todo
        // contador que sobe (centenas de restos); +100 sem multiplicar fica de fora pelo mesmo motivo
        static const int64_t ks[] = {2, 3, 4, 5, 8, 10, 16, 20, 25, 50, 100, 1000};
        int64_t k = d / dv, off = x1 - k * a;
        int nice = 0;
        for (size_t i = 0; i < sizeof ks / sizeof *ks; i++) nice |= k == ks[i];
        return nice && off >= -1000000 && off <= 1000000;
    }
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
    int needv = op == OP_EQ || op == OP_INCBY || op == OP_DECBY || op == OP_LIN || op == OP_XK;
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

// Só os resultados cujo endereço termina em "hex" (como o searchAddress com máscara do GameGuardian): o mesmo campo
// costuma cair sempre no mesmo final de endereço (ex.: ...A4) depois de reabrir. Desfaz com undo, como o refino.
static void cmd_addrend(const char *hex) {
    size_t n = strlen(hex);
    if (n < 1 || n > 8 || strspn(hex, "0123456789abcdefABCDEF") != n) { printf("err final de endereço inválido: %s\n", hex); return; }
    if (snapfd >= 0) { puts("err ainda são resultados demais: refine antes de filtrar pelo endereço"); return; }
    uint64_t want = strtoull(hex, NULL, 16), mask = (1ULL << (4 * n)) - 1;
    if (nres > capprev) {
        capprev = nres;
        prev = realloc(prev, capprev * sizeof *prev);
        if (!prev) { puts("err sem memória"); exit(1); }
    }
    memcpy(prev, res, nres * sizeof *res);
    nprev = nres;
    size_t k = 0;
    for (size_t i = 0; i < nres; i++) if ((res[i].addr & mask) == want) res[k++] = res[i];
    nres = k;
    printf("ok %zu\n", nres);
}

// Foto do jogo (antes/depois): a memória gravável inteira vai para um arquivo. Depois de uma ação no jogo (pegar a
// recompensa do correio), photodiff lista os Dwords que mudaram (fora o que muda sozinho e os ponteiros) e photorestore
// põe de volta o valor da foto: o jogo volta a ficar como estava antes da ação. Só para jogo offline.
static chunks_t photo;
static int photofd = -1;
static uint8_t *pnoise;  // 1 bit por Dword: mudou sozinho, sem ação; fica fora da diferença
static char photopath[64];

static void drop_photo(void) {
    if (photofd >= 0) { close(photofd); unlink(photopath); }
    photofd = -1;
    free(photo.c);
    photo = (chunks_t){0};
    free(pnoise);
    pnoise = NULL;
}

static void photo_chunk(size_t ci, unsigned char *buf, void *ctx) {
    (void)ctx;
    const chunk_t *c = &photo.c[ci];
    // ilegível agora: tudo vira "muda sozinho" (não entra na diferença nem volta)
    if (read_mem(c->addr, buf, c->len)) { memset(buf, 0, c->len); memset(pnoise + c->off / 32, 0xff, c->len / 32); }
    if (pwrite(photofd, buf, c->len, (off_t)c->off) != (ssize_t)c->len) memset(pnoise + c->off / 32, 0xff, c->len / 32);
    // grava já e tira do cache: GBs de foto no cache faziam o Android fechar o jogo e o Tinker por falta de memória
    sync_file_range(photofd, (off_t)c->off, c->len, SYNC_FILE_RANGE_WAIT_BEFORE | SYNC_FILE_RANGE_WRITE | SYNC_FILE_RANGE_WAIT_AFTER);
    posix_fadvise(photofd, (off_t)c->off, c->len, POSIX_FADV_DONTNEED);
}

// pilha das threads (pelo SP que /proc/pid/task/N/syscall mostra): sem nome no Linux, e voltar ela derruba o processo
static uint64_t stack_sp[256];
static int nstack_sp;

static void read_thread_sps(void) {
    nstack_sp = 0;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", target);
    DIR *d = opendir(path);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) && nstack_sp < 256) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char p2[320], buf[256];
        snprintf(p2, sizeof p2, "/proc/%d/task/%s/syscall", target, e->d_name);
        FILE *f = fopen(p2, "r");
        if (!f) continue;
        if (fgets(buf, sizeof buf, f)) {
            // "nr a1 a2 a3 a4 a5 a6 sp pc": o penúltimo é o SP (thread rodando mostra só "running")
            char *t[9], *sv = NULL; int n = 0;
            for (char *q = strtok_r(buf, " \n", &sv); q && n < 9; q = strtok_r(NULL, " \n", &sv)) t[n++] = q;
            if (n == 9) stack_sp[nstack_sp++] = strtoull(t[7], NULL, 16);
        }
        fclose(f);
    }
    closedir(d);
}

// faixas de endereço da foto ("photowin a-b,c-d"): o monitor vai afunilando — região, bloco, página — até o objeto
static uint64_t win_lo[512], win_hi[512];
static int nwin;

static void add_photo_chunks(uint64_t s, uint64_t e, int region, void *ctx) {
    for (int k = 0; k < nstack_sp; k++) if (stack_sp[k] >= s && stack_sp[k] < e) return;
    if (!nwin) { add_chunks(s, e, region, ctx); return; }
    for (int k = 0; k < nwin; k++) {
        uint64_t a = s > win_lo[k] ? s : win_lo[k], b = e < win_hi[k] ? e : win_hi[k];
        if (a < b) add_chunks(a, b, region, ctx);
    }
}

// "photowin off" ou "photowin 7a000000-7a100000,7b000000-7b010000" (hex, até 512 faixas, alinhadas a 4 bytes)
static void cmd_photowin(const char *arg) {
    nwin = 0;
    if (!arg || !strcmp(arg, "off")) { puts("ok 0"); return; }
    static char buf[16384];
    snprintf(buf, sizeof buf, "%s", arg);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok && nwin < 512; tok = strtok_r(NULL, ",", &save)) {
        char *dash = strchr(tok, '-');
        if (!dash) { nwin = 0; puts("err faixa inválida (use início-fim em hex)"); return; }
        uint64_t lo = strtoull(tok, NULL, 16) & ~3ULL, hi = strtoull(dash + 1, NULL, 16) & ~3ULL;
        if (hi <= lo) { nwin = 0; puts("err faixa vazia"); return; }
        win_lo[nwin] = lo; win_hi[nwin] = hi; nwin++;
    }
    printf("ok %d\n", nwin);
}

static void cmd_photo(void) {
    drop_photo();
    read_thread_sps();
    photo.want = ranges & ~((1u << 1) | (1u << 7));  // sem J (código e cache do Java) e sem pilha: mudam sozinhos e não guardam o jogo
    if (each_region(add_photo_chunks, &photo)) { puts("err não leu o mapa de memória"); return; }
    if (!photo.total) { puts("err nenhuma região marcada"); drop_photo(); return; }
    if (photo.total > (3ULL << 30)) {
        printf("err foto grande demais (%" PRIu64 " MB): em Ajustes deixe só Ca e A nas regiões e tente de novo\n", photo.total >> 20);
        drop_photo();
        return;
    }
    const char *dir = access("/data/local/tmp", W_OK) ? "/tmp" : "/data/local/tmp";
    struct statvfs fs;
    if (!statvfs(dir, &fs) && (uint64_t)fs.f_bavail * fs.f_frsize < photo.total + (64u << 20)) {
        printf("err sem espaço para a foto: precisa de %" PRIu64 " MB livres em %s; desmarque regiões em Ajustes\n", (photo.total >> 20) + 64, dir);
        drop_photo();
        return;
    }
    snprintf(photopath, sizeof photopath, "%s/tinker-photo", dir);
    photofd = open(photopath, O_RDWR | O_CREAT | O_TRUNC, 0600);
    pnoise = calloc(photo.total / 32 + 1, 1);
    if (photofd < 0 || !pnoise) { printf("err não criou %s: %s\n", photopath, strerror(errno)); drop_photo(); return; }
    parallel(photo.n, photo_chunk, NULL);
    printf("ok %" PRIu64 "\n", photo.total >> 20);
}

// endereço dentro da memória fotografada (um ponteiro de verdade aponta para ela)
static int in_photo(uint64_t v) { return in_chunks(&photo, v); }

// Dword dentro de um Qword que é ponteiro para a memória do jogo: voltar ele derrubaria o jogo (objeto já liberado).
// Só o que aponta para lá conta: dois números pequenos lado a lado (1 e 12445) não são ponteiro.
static int ptr_like(const unsigned char *b, size_t at, size_t len) {
    size_t q = at & ~(size_t)7;
    if (q + 8 > len) return 0;
    uint64_t v;
    memcpy(&v, b + q, 8);
    v &= 0x00ffffffffffffffULL;  // Android arm64 marca ponteiros do malloc no byte de cima
    return v >= 0x10000 && in_photo(v);
}

static void noise_chunk(size_t ci, unsigned char *buf, void *ctx) {
    (void)ctx;
    const chunk_t *c = &photo.c[ci];
    unsigned char *cur = buf, *old = buf + CHUNK;
    uint8_t *m = pnoise + c->off / 32;
    if (read_mem(c->addr, cur, c->len) || pread(photofd, old, c->len, (off_t)c->off) != (ssize_t)c->len) { memset(m, 0xff, c->len / 32); return; }
    for (size_t k = 0; k < c->len / 4; k++) if (memcmp(cur + k * 4, old + k * 4, 4)) m[k >> 3] |= 1 << (k & 7);
}

static void cmd_photonoise(void) {
    if (photofd < 0) { puts("err tire a foto antes"); return; }
    parallel(photo.n, noise_chunk, NULL);
    size_t n = 0;
    for (size_t i = 0; i < photo.total / 32; i++) n += __builtin_popcount(pnoise[i]);
    printf("ok %zu\n", n);
}

static void diff_chunk(size_t ci, unsigned char *buf, void *ctx) {
    hits_t *out = ctx;
    const chunk_t *c = &photo.c[ci];
    unsigned char *cur = buf, *old = buf + CHUNK;
    const uint8_t *m = pnoise + c->off / 32;
    if (read_mem(c->addr, cur, c->len) || pread(photofd, old, c->len, (off_t)c->off) != (ssize_t)c->len) return;
    for (size_t k = 0; k < c->len / 4; k++) {
        if ((m[k >> 3] & (1 << (k & 7))) || !memcmp(cur + k * 4, old + k * 4, 4)) continue;
        if (ptr_like(cur, k * 4, c->len) || ptr_like(old, k * 4, c->len)) continue;
        hpush(&out[ci], (hit_t){c->addr + k * 4, bits(cur + k * 4, 4), (uint16_t)T_I32, c->region});
    }
}

static void cmd_photodiff(void) {
    if (photofd < 0) { puts("err tire a foto antes"); return; }
    hits_t *out = calloc(photo.n ? photo.n : 1, sizeof *out);
    if (!out) { puts("err sem memória"); exit(1); }
    parallel(photo.n, diff_chunk, out);
    drop_snap();
    nres = nprev = 0;
    collect(out, photo.n);
    if (nres > list_max) nres = list_max;
    printf("ok %zu\n", nres);
}

// 👁 monitor: a foto passa a guardar o valor de agora (cada rodada compara com a anterior). "photonoise2": o que
// muda de novo 2 s depois é ruído (animação); um recurso quase nunca muda duas vezes em 2 s
static void rebase_chunk(size_t ci, unsigned char *buf, void *ctx) {
    uint8_t *seen = ctx;
    const chunk_t *c = &photo.c[ci];
    unsigned char *cur = buf, *old = buf + CHUNK;
    uint8_t *m = pnoise + c->off / 32, *s = seen + c->off / 32;
    if (read_mem(c->addr, cur, c->len) || pread(photofd, old, c->len, (off_t)c->off) != (ssize_t)c->len) return;
    int dirty = 0;
    for (size_t k = 0; k < c->len / 4; k++) {
        if (!memcmp(cur + k * 4, old + k * 4, 4)) continue;
        if (s[k >> 3] & (1 << (k & 7))) m[k >> 3] |= 1 << (k & 7);
        else s[k >> 3] |= 1 << (k & 7);
        dirty = 1;
    }
    if (dirty && pwrite(photofd, cur, c->len, (off_t)c->off) != (ssize_t)c->len) memset(m, 0xff, c->len / 32);
}

static void cmd_photonoise2(void) {
    if (photofd < 0) { puts("err tire a foto antes"); return; }
    uint8_t *seen = calloc(photo.total / 32 + 1, 1);
    if (!seen) { puts("err sem memória"); return; }
    parallel(photo.n, rebase_chunk, seen);
    usleep(2000000);
    parallel(photo.n, rebase_chunk, seen);
    free(seen);
    size_t n = 0;
    for (size_t i = 0; i < photo.total / 32; i++) n += __builtin_popcount(pnoise[i]);
    printf("ok %zu\n", n);
}

// "photostep N": o que mudou desde a rodada anterior, em degrau (1..10000) e com valor de recurso (1..10 milhões):
// "endereço antes depois região". Salto grande (lixo do GC, float, ponteiro) vira ruído e não volta mais
static void step_chunk(size_t ci, unsigned char *buf, void *ctx) {
    hits_t *out = ctx;
    const chunk_t *c = &photo.c[ci];
    unsigned char *cur = buf, *old = buf + CHUNK;
    uint8_t *m = pnoise + c->off / 32;
    if (read_mem(c->addr, cur, c->len) || pread(photofd, old, c->len, (off_t)c->off) != (ssize_t)c->len) return;
    int dirty = 0;
    for (size_t k = 0; k < c->len / 4; k++) {
        if ((m[k >> 3] & (1 << (k & 7))) || !memcmp(cur + k * 4, old + k * 4, 4)) continue;
        dirty = 1;
        int32_t o, v;
        memcpy(&o, old + k * 4, 4);
        memcpy(&v, cur + k * 4, 4);
        int64_t d = (int64_t)v - o;
        if (v < 1 || v > 10000000 || d < -10000 || d > 10000 || ptr_like(cur, k * 4, c->len)) { m[k >> 3] |= 1 << (k & 7); continue; }
        hpush(&out[ci], (hit_t){c->addr + k * 4, (uint32_t)o | (uint64_t)(uint32_t)v << 32, (uint16_t)T_I32, c->region});
    }
    if (dirty && pwrite(photofd, cur, c->len, (off_t)c->off) != (ssize_t)c->len) memset(m, 0xff, c->len / 32);
}

static void cmd_photostep(size_t n) {
    if (photofd < 0) { puts("err tire a foto antes"); return; }
    hits_t *out = calloc(photo.n ? photo.n : 1, sizeof *out);
    if (!out) { puts("err sem memória"); exit(1); }
    parallel(photo.n, step_chunk, out);
    drop_snap();
    nres = nprev = 0;
    collect(out, photo.n);
    size_t total = nres;
    for (size_t i = 0; i < nres && i < n; i++)
        printf("%" PRIx64 " %" PRId32 " %" PRId32 " %s\n", res[i].addr, (int32_t)(uint32_t)res[i].old, (int32_t)(uint32_t)(res[i].old >> 32), REGIONS[res[i].region]);
    nres = 0;  // antes|depois juntos no campo old: não servem para refinar
    printf("ok %zu\n", total);
}

// valor que o Dword tinha na foto
static int photo_value(uint64_t a, uint32_t *v) {
    size_t lo = 0, hi = photo.n;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        const chunk_t *c = &photo.c[mid];
        if (a < c->addr) hi = mid;
        else if (a + 4 > c->addr + c->len) lo = mid + 1;
        else return pread(photofd, v, 4, (off_t)(c->off + (a - c->addr))) == 4 ? 0 : -1;
    }
    return -1;
}

// "all": todos os resultados da lista (o que mudou, depois de refinar); ou um endereço só
static void cmd_photorestore(const char *what) {
    if (photofd < 0) { puts("err tire a foto antes"); return; }
    size_t ok = 0, fail = 0;
    uint32_t v;
    // "except A,B,C": todos menos esses (ex.: o contador de presentes, para não perder o que ganhou)
    int except = !strncmp(what, "except ", 7);
    if (!strcmp(what, "all") || except) {
        if (snapfd >= 0) { puts("err refine mais antes de voltar todos"); return; }
        for (size_t i = 0; i < nres; i++) {
            if (except) {
                char key[24];
                snprintf(key, sizeof key, "%" PRIx64, res[i].addr);
                const char *f = strstr(what + 7, key);
                size_t L = strlen(key);
                if (f && (f == what + 7 || f[-1] == ',') && (f[L] == ',' || !f[L] || f[L] == '\n')) continue;
            }
            if (!photo_value(res[i].addr, &v) && !write_mem(res[i].addr, &v, 4)) ok++;
            else fail++;
        }
    } else {
        uint64_t a = strtoull(what, NULL, 16);
        if (!photo_value(a, &v) && !write_mem(a, &v, 4)) ok++;
        else fail++;
    }
    printf("ok %zu %zu\n", ok, fail);
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
    if (t < 0 || (t & (XOR | TAG | LUA))) { printf("err tipo inválido: %s\n", name); return; }
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
// mode: 0 fixo; 1 "up" (só deixa subir: o jogo não tira, mas o que ganhar fica); 2 "down" (só deixa descer)
typedef struct { uint64_t addr; int type, fails, mode; unsigned char val[8]; } frz_t;
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
            // com regra: o valor andou para o lado permitido; vira o novo congelado (não escreve)
            if (f->mode && !load(f->addr, f->type, cur, 0)) {
                double c = num(B(f->type), cur), v = num(B(f->type), f->val);
                if (f->mode == 1 ? c >= v : c <= v) { memcpy(f->val, cur, len); f->fails = 0; frz[k++] = *f; continue; }
            }
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

static void cmd_freeze(const char *addr, const char *name, const char *value, const char *rule) {
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
    frz[i] = (frz_t){a, t, 0, !strcmp(rule, "up") ? 1 : !strcmp(rule, "down") ? 2 : 0, {0}};
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

// O que o campo aponta, para a ficha: string (Il2CppString: tamanho e UTF-16 depois de klass e monitor) vira o texto;
// array (Il2CppArray: max_length depois de klass, monitor e bounds) e List<T> (_items, _size) viram quantos itens.
static void field_extra(uint64_t at, unsigned off, int code) {
    uint64_t p, items;
    int32_t n = -1, cap;
    if (read_ptr(at, &p) || p < 0x10000) return;
    if (code == 0x0e) {
        uint16_t w[64];
        char t[64 * 3 + 1], *o = t;
        if (read_mem(p + 2 * psize, &n, 4) || n < 0 || n > 4096) return;
        int k = n < 64 ? n : 64;
        if (k && read_mem(p + 2 * psize + 4, w, (size_t)k * 2)) return;
        for (int i = 0; i < k; i++) {  // UTF-8 para o app; controle e quebra de linha viram espaço
            unsigned c = w[i];
            if (c < 0x20) *o++ = ' ';
            else if (c < 0x80) *o++ = (char)c;
            else if (c < 0x800) { *o++ = (char)(0xc0 | c >> 6); *o++ = (char)(0x80 | (c & 0x3f)); }
            else if (c >= 0xd800 && c < 0xe000) *o++ = '?';
            else { *o++ = (char)(0xe0 | c >> 12); *o++ = (char)(0x80 | (c >> 6 & 0x3f)); *o++ = (char)(0x80 | (c & 0x3f)); }
        }
        *o = 0;
        printf("text %x %d %s%s\n", off, n, t, n > 64 ? "…" : "");
    } else if (code == 0x14 || code == 0x1d) {
        if (read_mem(p + 3 * psize, &n, 4) || n < 0 || n > 10000000) return;
        printf("count %x %d\n", off, n);
    } else if (code == 0x15) {
        // List<T>: _items (array) logo depois de klass e monitor, _size depois dele; só se o array confere
        if (read_ptr(p + 2 * psize, &items) || items < 0x10000 || read_mem(p + 3 * psize, &n, 4)
            || read_mem(items + 3 * psize, &cap, 4) || n < 0 || cap < n || cap > 10000000) return;
        printf("count %x %d\n", off, n);
    }
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
            int code = (bits >> 16) & 0xff;
            printf("field %x %02x %s\n", (unsigned)off, code, nm);
            field_extra(s + (uint64_t)off, (unsigned)off, code);
        }
        puts("ok");
        return;
    }
    puts("err não achei a classe IL2CPP (o jogo não é Unity IL2CPP, ou o valor não está dentro de um objeto)");
}

// Métodos de uma classe IL2CPP: o vetor de MethodInfo* da classe (o lugar muda entre versões do Unity, como o dos campos).
// MethodInfo começa com o endereço do código; o nome e a classe dona vêm logo depois (1 a 4 ponteiros adiante, conforme a
// versão); depois deles, o tipo de retorno. Só os que têm código (abstratos e genéricos abertos ficam de fora).
static int where_of(uint64_t a, char *out, size_t len);

static int method_layout(uint64_t m, uint64_t k, int *nameoff) {
    char tmp[64];
    for (int j = 1; j <= 4; j++) {
        uint64_t pn, pk;
        if (read_ptr(m + j * psize, &pn) || read_ptr(m + (j + 1) * psize, &pk) || pk != k) continue;
        if (!ident(pn, tmp, sizeof tmp) || !tmp[0]) continue;
        *nameoff = j * psize;
        return 1;
    }
    return 0;
}

static void cmd_il2methods(const char *addr) {
    psize = ptr_size();
    uint64_t a = strtoull(addr, NULL, 16), k = a, arr = 0, m;
    klass_t kl;
    if (!read_klass(k, &kl) && (read_ptr(a, &k) || !read_klass(k, &kl))) { puts("err não achei a classe IL2CPP nesse endereço"); return; }
    int noff = 0;
    for (int off = 4 * psize; off < 0x200 && !arr; off += psize) {
        uint64_t p;
        if (read_ptr(k + off, &p) || p < 0x10000 || read_ptr(p, &m) || m < 0x10000) continue;
        if (method_layout(m, k, &noff)) arr = p;
    }
    if (!arr) { puts("err a classe não tem métodos com código"); return; }
    int n = 0;
    for (int i = 0; i < 4096; i++) {
        uint64_t code, pn, pk, rt;
        if (read_ptr(arr + (uint64_t)i * psize, &m) || m < 0x10000) break;
        if (read_ptr(m + noff + psize, &pk) || pk != k) break;
        char nm[96], w[160];
        uint32_t bits = 0;
        if (read_ptr(m, &code) || read_ptr(m + noff, &pn) || !ident(pn, nm, sizeof nm)) continue;
        if (!read_ptr(m + noff + 2 * psize, &rt) && rt >= 0x10000) read_mem(rt + psize, &bits, 4);
        if (code < 0x10000) continue;
        if (where_of(code, w, sizeof w)) snprintf(w, sizeof w, "?");
        printf("method %" PRIx64 " %02x %s %s\n", code, (bits >> 16) & 0xff, w, nm);
        n++;
    }
    printf("ok %d\n", n);
}

// ---- Lua 5.3/5.4 (xLua, ToLua, SLua): a tabela em volta de um número, com o nome de cada campo ----
// Table: tt 5 em +8, lsizenode em +11, tamanho da lista em +12, lista em +16, nós em +24 (igual nas duas versões).
// Nó: 5.4 = 24 bytes (tipo do valor em +8, tipo da chave em +9, chave em +16); 5.3 = 32 bytes (chave em +16, tipo dela em +24).
// TString: tt 4 (curta) ou 0x14 (longa) em +8, tamanho em +11 (curta) ou +16 (longa), texto em +24.
static int luaver = 54;  // a última versão reconhecida (decide se 3 é inteiro ou decimal)

static int lua_vt(int x) {  // tipos de valor que existem nas duas versões (0x10/0x20: vazio do 5.4)
    static const unsigned char ok[] = {0x00, 0x01, 0x11, 0x02, 0x03, 0x13, 0x10, 0x20, 0x44, 0x54, 0x45, 0x46, 0x16, 0x66, 0x47, 0x48};
    for (size_t i = 0; i < sizeof ok; i++) if (ok[i] == x) return 1;
    return 0;
}

static uint64_t lua_ptr(uint64_t v) { return v & TAGMASK; }

// lê o nó (layout 54 ou 53): tipo do valor, tipo da chave e a chave
static int lua_node(uint64_t n, int ver, int *vt, int *kt, uint64_t *key) {
    unsigned char b[32];
    if (read_mem(n, b, ver == 54 ? 24 : 32)) return -1;
    uint32_t t53, k53;
    memcpy(&t53, b + 8, 4); memcpy(&k53, b + 24, 4);
    *vt = ver == 54 ? b[8] : (t53 < 0x100 ? (int)t53 : -1);
    *kt = ver == 54 ? b[9] : (k53 < 0x100 ? (int)k53 : -1);
    memcpy(key, b + 16, 8);
    if (!lua_vt(*vt) || !(lua_vt(*kt) || *kt == 0x0a || *kt == 0x0b)) return -1;
    return 0;
}

// texto de uma TString: só letras visíveis (espaço vira _), até 60
static int lua_str(uint64_t p, char *out, size_t n) {
    unsigned char h[24];
    p = lua_ptr(p);
    if (p < 0x10000 || read_mem(p, h, 24) || (h[8] != 0x04 && h[8] != 0x14)) return -1;
    uint64_t len = h[11];
    if (h[8] == 0x14) memcpy(&len, h + 16, 8);
    if (len > n - 1) len = n - 1;
    if (len > 60) len = 60;
    if (read_mem(p + 24, out, len)) return -1;
    out[len] = 0;
    for (char *c = out; *c; c++) if ((unsigned char)*c <= ' ' || *c == 0x7f) *c = '_';
    return 0;
}

// um campo: "lua <endereço do valor> <tipo> <nome> <valor>"
static void lua_print(uint64_t va, int vt, const char *key) {
    unsigned char b[8];
    if (read_mem(va, b, 8)) return;
    int64_t i; double d; uint64_t p;
    memcpy(&i, b, 8); memcpy(&d, b, 8); memcpy(&p, b, 8);
    int isint = luaver == 54 ? vt == 0x03 : vt == 0x13;
    char txt[64];
    if (vt == 0x03 || vt == 0x13) { if (isint) printf("lua %" PRIx64 " int %s %" PRId64 "\n", va, key, i); else printf("lua %" PRIx64 " num %s %.15g\n", va, key, d); }
    else if (vt == 0x44 || vt == 0x54) printf("lua %" PRIx64 " str %s %s\n", va, key, lua_str(p, txt, sizeof txt) ? "?" : txt);
    else if (vt == 0x45) printf("lua %" PRIx64 " table %s %" PRIx64 "\n", va, key, lua_ptr(p));
    else if (vt == 0x01 || vt == 0x11) printf("lua %" PRIx64 " bool %s %s\n", va, key, (luaver == 54 ? vt == 0x11 : i != 0) ? "true" : "false");
    else if (vt == 0x46 || vt == 0x16 || vt == 0x66) printf("lua %" PRIx64 " func %s %" PRIx64 "\n", va, key, lua_ptr(p));
    else if (vt == 0x47 || vt == 0x02) printf("lua %" PRIx64 " udata %s %" PRIx64 "\n", va, key, lua_ptr(p));
}

// nome da chave: texto, número inteiro ([3]) ou ? para outros tipos
static void lua_keyname(int kt, uint64_t key, char *out, size_t n) {
    char t[64];
    int64_t i; memcpy(&i, &key, 8);
    if ((kt == 0x44 || kt == 0x54) && !lua_str(key, t, sizeof t)) snprintf(out, n, "%s", t);
    else if (kt == (luaver == 54 ? 0x03 : 0x13)) snprintf(out, n, "[%" PRId64 "]", i);
    else snprintf(out, n, "?");
}

// conta nós válidos com chave de texto a partir de n (para escolher entre 5.3 e 5.4)
static int lua_score(uint64_t n, int ver, int count) {
    int sc = 0, vt, kt;
    uint64_t key;
    char t[64];
    for (int k = 0; k < count; k++) {
        if (lua_node(n + (uint64_t)k * (ver == 54 ? 24 : 32), ver, &vt, &kt, &key)) return k ? sc : -1;
        if ((kt == 0x44 || kt == 0x54) && !lua_str(key, t, sizeof t)) sc++;
    }
    return sc;
}

static int lua_nodes(uint64_t n, int ver, size_t count) {
    int shown = 0, vt, kt;
    uint64_t key;
    char name[80];
    size_t step = ver == 54 ? 24 : 32;
    for (size_t k = 0; k < count && shown < 500; k++) {
        if (lua_node(n + k * step, ver, &vt, &kt, &key)) continue;
        if (kt == 0 || kt == 0x0a || kt == 0x0b || vt == 0 || vt == 0x10 || vt == 0x20) continue;  // vazio ou apagado
        lua_keyname(kt, key, name, sizeof name);
        if (!strcmp(name, "?")) continue;  // chave que não é texto nem número: quase sempre memória vizinha que só parece um nó
        lua_print(n + k * step, vt, name);
        shown++;
    }
    return shown;
}

// luatable ADDR: ADDR é a própria tabela (tt 5) ou o endereço de um valor dentro dela (um resultado da busca li/lf)
static void cmd_luatable(const char *addr) {
    uint64_t a = strtoull(addr, NULL, 16);
    unsigned char h[32];
    int n = 0;
    if (!read_mem(a, h, 32) && h[8] == 0x05 && h[11] <= 24) {
        uint64_t arr, node;
        uint32_t asize;
        memcpy(&asize, h + 12, 4); memcpy(&arr, h + 16, 8); memcpy(&node, h + 24, 8);
        node = lua_ptr(node); arr = lua_ptr(arr);
        size_t cnt = (size_t)1 << h[11];
        if (cnt > 4096) cnt = 4096;
        int s54 = lua_score(node, 54, (int)cnt), s53 = lua_score(node, 53, (int)cnt);
        if (s54 < 0 && s53 < 0 && !asize) { puts("err não parece uma tabela do Lua"); return; }
        luaver = s53 > s54 ? 53 : 54;
        printf("table %" PRIx64 " lua%d\n", a, luaver);
        // parte de lista (chaves 1, 2, 3…): TValue de 16 bytes
        for (uint32_t k = 0; k < asize && k < 200 && arr >= 0x10000; k++) {
            unsigned char t[9];
            char name[24];
            if (read_mem(arr + k * 16ULL, t, 9)) break;
            if (!lua_vt(t[8]) || t[8] == 0 || t[8] == 0x10) continue;
            snprintf(name, sizeof name, "[%u]", k + 1);
            lua_print(arr + k * 16ULL, t[8], name);
            n++;
        }
        if (s54 >= 0 || s53 >= 0) n += lua_nodes(node, luaver, cnt);
        printf("ok %d\n", n);
        return;
    }
    // dentro da tabela: o nó do valor; anda para os lados enquanto os nós fazem sentido
    // shortcut: sem achar o começo exato da tabela, pode pegar nós de uma vizinha colada; achar a Table pelo ponteiro quando incomodar
    int best = 0, bsc = -1;
    for (int ver = 54; ver >= 53; ver--) { int sc = lua_score(a, ver, 1); if (sc > bsc) { bsc = sc; best = ver; } }
    if (bsc < 0) { puts("err não é um campo de tabela do Lua (ou está numa lista)"); return; }
    luaver = best;
    size_t step = best == 54 ? 24 : 32;
    uint64_t lo = a, hi = a;
    int vt, kt;
    uint64_t key;
    for (int k = 0; k < 2048 && !lua_node(lo - step, best, &vt, &kt, &key); k++) lo -= step;
    for (int k = 0; k < 2048 && !lua_node(hi + step, best, &vt, &kt, &key); k++) hi += step;
    printf("nodes %" PRIx64 " lua%d\n", lo, luaver);
    n = lua_nodes(lo, best, (hi - lo) / step + 1);
    printf("ok %d\n", n);
}

// ---- onde o jogo escreve: "activity start" marca todas as páginas como limpas (soft-dirty do kernel); o jogo carrega ou
// joga; "activity" conta, por região (Ca, A, Jh…), quanto foi escrito desde então. Mostra onde procurar, não o valor.
typedef struct { int fd; long pg; uint64_t dirty[11], total[11]; } act_t;

static void act_region(uint64_t s, uint64_t e, int region, void *ctx) {
    act_t *a = ctx;
    if (region < 0 || region > 10) return;
    uint64_t buf[4096];
    a->total[region] += (e - s) / (uint64_t)a->pg;
    for (uint64_t p = s; p < e;) {
        size_t n = (size_t)((e - p) / (uint64_t)a->pg);
        if (n > 4096) n = 4096;
        if (!n) break;
        ssize_t r = pread(a->fd, buf, n * 8, (off_t)(p / (uint64_t)a->pg * 8));
        if (r <= 0) break;
        size_t got = (size_t)r / 8;
        for (size_t k = 0; k < got; k++) if (buf[k] >> 55 & 1) a->dirty[region]++;  // bit 55: escrita desde a marcação
        p += got * (uint64_t)a->pg;
    }
}

// reserva quando o kernel não marca (sem soft-dirty): o "Private_Dirty" de cada região no smaps só cresce quando o jogo
// escreve em páginas novas; a diferença entre o começo e agora mostra onde o carregamento guardou coisa nova
static uint64_t act_base[11];

static int smaps_dirty(uint64_t out[11]) {
    char path[64], line[1024];
    snprintf(path, sizeof path, "/proc/%d/smaps", target);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    memset(out, 0, 11 * sizeof *out);
    int region = -1;
    while (fgets(line, sizeof line, f)) {
        unsigned long long s, e;
        char perms[8];
        int off = 0;
        if (sscanf(line, "%llx-%llx %7s %*s %*s %*s %n", &s, &e, perms, &off) >= 3 && strchr(line, '-') < strchr(line, ' ')) {
            char *name = off ? line + off : line + strlen(line);
            name[strcspn(name, "\n")] = 0;
            region = perms[1] == 'w' ? region_of(perms, name) : -1;
            continue;
        }
        unsigned long kb;
        if (region >= 0 && region <= 10 && sscanf(line, "Private_Dirty: %lu kB", &kb) == 1) out[region] += kb;
    }
    fclose(f);
    return 0;
}

static void cmd_activity(const char *arg) {
    char path[64];
    if (arg && !strcmp(arg, "start")) {
        if (smaps_dirty(act_base)) { puts("err não leu o smaps do jogo"); return; }
        snprintf(path, sizeof path, "/proc/%d/clear_refs", target);
        int fd = open(path, O_WRONLY);
        if (fd >= 0) { if (write(fd, "4", 1) != 1) { /* sem soft-dirty: fica só a reserva */ } close(fd); }
        puts("ok");
        return;
    }
    act_t a = {.pg = sysconf(_SC_PAGESIZE)};
    snprintf(path, sizeof path, "/proc/%d/pagemap", target);
    a.fd = open(path, O_RDONLY);
    if (a.fd < 0) { printf("err não abriu o pagemap: %s\n", strerror(errno)); return; }
    int r = each_region(act_region, &a);
    close(a.fd);
    if (r) { puts("err não leu o mapa de memória"); return; }
    uint64_t sum = 0, kb[11], now[11];
    for (int i = 0; i < 11; i++) sum += kb[i] = a.dirty[i] * (uint64_t)a.pg >> 10;
    int soft = sum > 0;
    if (!soft) {  // o kernel não marcou nada: usa o quanto cada região ganhou de páginas escritas
        if (smaps_dirty(now)) { puts("err não leu o smaps do jogo"); return; }
        for (int i = 0; i < 11; i++) sum += kb[i] = now[i] > act_base[i] ? now[i] - act_base[i] : 0;
    }
    for (int i = 0; i < 11; i++)
        if (a.total[i]) printf("act %s %" PRIu64 " %" PRIu64 "\n", REGIONS[i], kb[i], a.total[i] * (uint64_t)a.pg >> 10);
    printf("how %s\n", soft ? "soft" : "smaps");
    printf("ok %" PRIu64 "\n", sum);
}

// ---- dados do autoteste do app: um daemon se liga a ele mesmo e testa Lua, foto e "onde escreve" nestes, sem tocar no jogo ----
static void cmd_selftestdata(void) {
    static unsigned char *tbl, *fresh;
    if (!tbl) {
        // tabela do Lua 5.4 como a do xLua: { gold = 31415 }
        static unsigned char str[64], nodes[2 * 24], t[64];
        str[8] = 0x04; str[11] = 4; memcpy(str + 24, "gold", 4);
        long long gold = 31415;
        void *sp = str;
        memcpy(nodes, &gold, 8); nodes[8] = 0x03; nodes[9] = 0x44; memcpy(nodes + 16, &sp, 8);
        void *np = nodes;
        t[8] = 0x05; t[11] = 1; memcpy(t + 24, &np, 8);
        tbl = t;
        fresh = mmap(NULL, 1 << 20, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);  // ainda não escrita
        if (fresh == MAP_FAILED) fresh = NULL;
    }
    printf("lua %" PRIx64 "\n", (uint64_t)(uintptr_t)tbl);
    printf("fresh %" PRIx64 "\n", (uint64_t)(uintptr_t)fresh);
    puts("ok");
}

// "o sistema deixa o Tinker se ligar a um processo como depurador?" É a base do speedhack e do unrandomizer (que precisam
// rodar código dentro do jogo). Testa num processo filho que só dorme: o jogo não é tocado.
static void cmd_canattach(void) {
    pid_t c = fork();
    if (c < 0) { printf("err fork: %s\n", strerror(errno)); return; }
    if (!c) { for (;;) pause(); }
    int ok = ptrace(PTRACE_ATTACH, c, 0, 0) == 0, why = errno, st = 0;
    if (ok) { waitpid(c, &st, __WALL); ptrace(PTRACE_DETACH, c, 0, 0); }
    kill(c, SIGKILL);
    waitpid(c, &st, 0);
    if (ok) puts("ok sim");
    else printf("ok não (%s)\n", strerror(why));
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

// Onde fica um endereço: "lib.so+deslocamento" (a lib de base mais alta que não passa dele). Código dentro de uma lib
// fica sempre no mesmo deslocamento, então um patch guardado assim vale em qualquer vez que o jogo abrir.
static int where_of(uint64_t a, char *out, size_t len) {
    if (mods_pid != target) { psize = ptr_size(); if (load_modules()) return -1; }
    int best = -1;
    for (size_t i = 0; i < nmods; i++) if (mods[i].base <= a && (best < 0 || mods[i].base > mods[best].base)) best = (int)i;
    if (best < 0 || a - mods[best].base > 0x20000000) return -1;
    snprintf(out, len, "%s+%" PRIx64, mods[best].name, a - mods[best].base);
    return 0;
}

static void cmd_where(const char *addr) {
    char w[160];
    if (where_of(strtoull(addr, NULL, 16), w, sizeof w)) puts("err fora das libs do jogo");
    else printf("ok %s\n", w);
}

// ---- Funções das libs do jogo sem IDA nem Ghidra: os nomes vêm da tabela de símbolos da própria lib (.dynsym),
// o começo de cada função da tabela que o Android usa para desenrolar a pilha (.eh_frame_hdr), e as chamadas das
// instruções BL (ARM64) e CALL (x86_64). Tudo lido da memória do jogo: vale também para lib dentro do APK.
typedef struct { uint64_t bias, symtab, strtab, strsz, eh; size_t nsym; int machine; } elf_t;
typedef struct { uint64_t a; uint32_t name; } fsym_t;

static int elf_open(const module_t *m, elf_t *e) {
    Elf64_Ehdr h;
    Elf64_Phdr ph[64];
    memset(e, 0, sizeof *e);
    if (read_mem(m->base, &h, sizeof h) || h.e_ident[EI_CLASS] != ELFCLASS64 || h.e_phnum > 64 ||
        read_mem(m->base + h.e_phoff, ph, h.e_phnum * sizeof *ph)) return -1;
    uint64_t lo = UINT64_MAX, dyn = 0, dynsz = 0;
    for (int i = 0; i < h.e_phnum; i++) {
        if (ph[i].p_type == PT_LOAD && ph[i].p_vaddr < lo) lo = ph[i].p_vaddr & ~0xfffULL;
        if (ph[i].p_type == PT_DYNAMIC) { dyn = ph[i].p_vaddr; dynsz = ph[i].p_memsz; }
        if (ph[i].p_type == PT_GNU_EH_FRAME) e->eh = ph[i].p_vaddr;
    }
    if (lo == UINT64_MAX) return -1;
    e->bias = m->base - lo;
    e->machine = h.e_machine;
    if (e->eh) e->eh += e->bias;
    uint64_t hash = 0, gnu = 0;
    Elf64_Dyn d[256];
    size_t nd = dynsz / sizeof *d < 256 ? dynsz / sizeof *d : 256;
    if (dyn && nd && !read_mem(e->bias + dyn, d, nd * sizeof *d)) {
        for (size_t i = 0; i < nd && d[i].d_tag != DT_NULL; i++) {
            // o linker do Android deixa o endereço sem somar a base; o do Linux (glibc) às vezes soma
            uint64_t v = d[i].d_un.d_ptr < e->bias ? d[i].d_un.d_ptr + e->bias : d[i].d_un.d_ptr;
            if (d[i].d_tag == DT_SYMTAB) e->symtab = v;
            else if (d[i].d_tag == DT_STRTAB) e->strtab = v;
            else if (d[i].d_tag == DT_STRSZ) e->strsz = d[i].d_un.d_val;
            else if (d[i].d_tag == DT_HASH) hash = v;
            else if (d[i].d_tag == DT_GNU_HASH) gnu = v;
        }
    }
    uint32_t hv[4];
    if (hash && !read_mem(hash, hv, 8)) e->nsym = hv[1];
    else if (gnu && !read_mem(gnu, hv, 16) && hv[0] && hv[0] < 1000000) {
        // GNU hash não diz quantos símbolos: o último é o fim da corrente do maior balde
        uint32_t *b = malloc(hv[0] * 4ULL), last = 0, c = 0;
        uint64_t chains = gnu + 16 + (uint64_t)hv[2] * 8 + (uint64_t)hv[0] * 4;
        if (b && !read_mem(gnu + 16 + (uint64_t)hv[2] * 8, b, hv[0] * 4ULL))
            for (uint32_t i = 0; i < hv[0]; i++) if (b[i] > last) last = b[i];
        free(b);
        if (last >= hv[1]) {
            while (last - hv[1] < 2000000 && !read_mem(chains + (uint64_t)(last - hv[1]) * 4, &c, 4) && !(c & 1)) last++;
            e->nsym = last + 1;
        } else e->nsym = hv[1];
    }
    if (e->nsym > 2000000) e->nsym = 0;
    return 0;
}

// Funções com nome da lib, em ordem de endereço (devolve quantas; *out é do chamador)
static int cmp_fsym(const void *a, const void *b) {
    uint64_t x = ((const fsym_t *)a)->a, y = ((const fsym_t *)b)->a;
    return x < y ? -1 : x > y;
}

static size_t elf_funcs(const elf_t *e, fsym_t **out) {
    *out = NULL;
    if (!e->symtab || !e->strtab || !e->nsym) return 0;
    Elf64_Sym *s = malloc(e->nsym * sizeof *s);
    fsym_t *f = malloc(e->nsym * sizeof *f);
    size_t n = 0;
    if (s && f && !read_mem(e->symtab, s, e->nsym * sizeof *s))
        for (size_t i = 0; i < e->nsym; i++)
            if (ELF64_ST_TYPE(s[i].st_info) == STT_FUNC && s[i].st_value && s[i].st_shndx != SHN_UNDEF && s[i].st_name < e->strsz)
                f[n++] = (fsym_t){e->bias + s[i].st_value, s[i].st_name};
    free(s);
    if (!n) { free(f); return 0; }
    qsort(f, n, sizeof *f, cmp_fsym);
    *out = f;
    return n;
}

static void elf_name(const elf_t *e, uint32_t off, char *out, size_t len) {
    out[0] = 0;
    if (read_mem(e->strtab + off, out, len - 1)) { size_t i = 0; while (i < len - 1 && !read_mem(e->strtab + off + i, out + i, 1) && out[i]) i++; out[i] = 0; }
    out[len - 1] = 0;
    for (char *c = out; *c; c++) if (*c == ' ' || *c == '\n') *c = '_';
}

// Começos de função da .eh_frame_hdr (tabela já ordenada); só o formato que clang e gcc gravam (datarel sdata4)
static size_t elf_starts(const elf_t *e, uint64_t **out) {
    unsigned char h[12];
    *out = NULL;
    if (!e->eh || read_mem(e->eh, h, 12) || h[0] != 1 || h[3] != 0x3b || (h[1] & 0x0f) != 0x0b || h[2] != 0x03) return 0;
    uint32_t n;
    memcpy(&n, h + 8, 4);
    if (!n || n > 2000000) return 0;
    int32_t *t = malloc(n * 8ULL);
    uint64_t *s = malloc(n * sizeof *s);
    if (!t || !s || read_mem(e->eh + 12, t, n * 8ULL)) { free(t); free(s); return 0; }
    for (uint32_t i = 0; i < n; i++) s[i] = e->eh + (int64_t)t[2 * i];
    free(t);
    *out = s;
    return n;
}

static int game_module(uint64_t a) {
    if (mods_pid != target) { psize = ptr_size(); if (load_modules()) return -1; }
    int best = -1;
    for (size_t i = 0; i < nmods; i++) if (mods[i].base <= a && (best < 0 || mods[i].base > mods[best].base)) best = (int)i;
    return best < 0 || a - mods[best].base > 0x20000000 ? -1 : best;
}

// "syms gold": funções com nome nas libs do jogo (fora as do sistema) que contêm o texto; vazio ou * = todas
static void cmd_syms(const char *filter) {
    if (mods_pid != target) { psize = ptr_size(); if (load_modules()) { puts("err sem mapa"); return; } }
    const char *f = !filter[0] || !strcmp(filter, "*") ? "" : filter;
    size_t shown = 0, total = 0;
    char name[160];
    for (size_t m = 0; m < nmods; m++) {
        elf_t e;
        fsym_t *fs;
        // TINKER_ALL_LIBS: o teste no Linux, onde o alvo não fica em /data
        if ((mods[m].system && !getenv("TINKER_ALL_LIBS")) || elf_open(&mods[m], &e)) continue;
        size_t n = elf_funcs(&e, &fs);
        for (size_t i = 0; i < n; i++) {
            elf_name(&e, fs[i].name, name, sizeof name);
            if (!name[0] || (f[0] && !strcasestr(name, f))) continue;
            total++;
            // tamanho = até a próxima função com nome (a tabela de símbolos nem sempre traz)
            if (shown++ < 300) printf("sym %" PRIx64 " %" PRIu64 " %s %s\n", fs[i].a, i + 1 < n ? fs[i + 1].a - fs[i].a : 0, mods[m].name, name);
        }
        free(fs);
    }
    printf("ok %zu\n", total);
}

// Função que contém addr: começo e fim (eh_frame), nome (símbolo) e a arquitetura da lib
static int func_of(uint64_t a, uint64_t *start, uint64_t *end, char *name, size_t len, int *machine) {
    int m = game_module(a);
    elf_t e;
    if (m < 0 || elf_open(&mods[m], &e)) return -1;
    *machine = e.machine;
    uint64_t *st;
    size_t n = elf_starts(&e, &st);
    fsym_t *fs;
    size_t nf = elf_funcs(&e, &fs);
    *start = *end = 0;
    size_t lo = 0, hi = n;
    while (lo < hi) { size_t mid = (lo + hi) / 2; if (st[mid] <= a) lo = mid + 1; else hi = mid; }
    if (lo) { *start = st[lo - 1]; *end = lo < n ? st[lo] : *start + 0x1000; }
    // sem eh_frame: o símbolo com nome logo antes
    lo = 0; hi = nf;
    while (lo < hi) { size_t mid = (lo + hi) / 2; if (fs[mid].a <= a) lo = mid + 1; else hi = mid; }
    name[0] = 0;
    if (lo && fs[lo - 1].a >= *start) {
        if (!*start || fs[lo - 1].a > *start) { *start = fs[lo - 1].a; if (lo < nf && (!*end || fs[lo].a < *end)) *end = fs[lo].a; }
        if (fs[lo - 1].a == *start) elf_name(&e, fs[lo - 1].name, name, len);
    }
    free(st);
    free(fs);
    if (!*start) return -1;
    if (!*end || *end - *start > 0x100000) *end = *start + 0x1000;
    return 0;
}

// ---- Unreal 4.23+ e 5: nomes (FNamePool) e objetos (GUObjectArray), lidos da memória como os dumpers fazem ----
// O bloco 0 dos nomes começa sempre com "None" e "ByteProperty" (o app acha com aobfind e passa o endereço); a tabela
// dos blocos e a dos objetos ficam nos dados da lib do jogo e são achadas pelo formato. UObject (64 bits): classe em
// +0x10, nome (índice do FName) em +0x18, índice interno em +0xC.
static uint64_t ue_blocks[8192];
static int ue_nblocks;

static int ue_name(uint32_t idx, char *out, size_t n) {
    uint32_t b = idx >> 16, off = (idx & 0xffff) * 2;
    out[0] = 0;
    if (b >= (uint32_t)ue_nblocks || n < 2) return -1;
    uint16_t h;
    if (read_mem(ue_blocks[b] + off, &h, 2)) return -1;
    int len = h >> 6, wide = h & 1;
    if (len <= 0) return -1;
    if (wide) {  // UTF-16: fica a parte ASCII
        uint16_t w[256];
        int l = len < 255 ? len : 255;
        if (read_mem(ue_blocks[b] + off + 2, w, l * 2)) return -1;
        size_t k = 0;
        for (int i = 0; i < l && k + 1 < n; i++) out[k++] = w[i] >= 32 && w[i] < 127 ? (char)w[i] : '?';
        out[k] = 0;
    } else {
        int l = (size_t)len < n - 1 ? len : (int)n - 1;
        if (read_mem(ue_blocks[b] + off + 2, out, l)) return -1;
        out[l] = 0;
    }
    for (char *c = out; *c; c++) if (*c == ' ' || *c == '\n') *c = '_';
    return 0;
}

// cada valor de 8 bytes dos dados graváveis das libs do jogo passa por fn; devolve o endereço do primeiro aceito
typedef int (*qw_fn)(uint64_t at, uint64_t v, void *ctx);
static uint64_t scan_qwords(qw_fn fn, void *ctx) {
    for (size_t r = 0; r < nsr; r++) {
        if (mods[sr[r].mod].system && !getenv("TINKER_ALL_LIBS")) continue;
        size_t len = sr[r].end - sr[r].start;
        if (len > (64u << 20)) continue;
        uint64_t *buf = malloc(len);
        if (!buf) continue;
        if (!read_mem(sr[r].start, buf, len))
            for (size_t i = 0; i < len / 8; i++)
                if (fn(sr[r].start + i * 8, buf[i], ctx)) { uint64_t a = sr[r].start + i * 8; free(buf); return a; }
        free(buf);
    }
    return 0;
}

static int is_blocks(uint64_t at, uint64_t v, void *ctx) {
    uint64_t next;
    (void)at;
    return v == *(uint64_t *)ctx && !read_mem(at + 8, &next, 8) && (next == 0 || (next & TAGMASK) > 0x10000);
}

static int ue_item = 24;
static uint32_t ue_index(uint64_t chunk, int i, int item) {
    uint64_t obj;
    uint32_t idx = 0xffffffff;
    if (read_mem(chunk + (uint64_t)i * item, &obj, 8) || !(obj & TAGMASK)) return 0xffffffff;
    if (read_mem((obj & TAGMASK) + 0xc, &idx, 4)) return 0xffffffff;
    return idx;
}

// FChunkedFixedUObjectArray: Objects (ponteiro para os pedaços), PreAllocated, Max, Num, MaxChunks, NumChunks
static int is_objects(uint64_t at, uint64_t v, void *ctx) {
    (void)ctx;
    v &= TAGMASK;
    if (v < 0x10000 || (v & 7)) return 0;
    int32_t f[4];
    if (read_mem(at + 16, f, 16) || f[1] < 3 || f[1] > f[0] || f[0] > 100000000 || f[3] < 1 || f[3] > f[2] || f[2] > 5000) return 0;
    uint64_t chunk;
    if (read_mem(v, &chunk, 8) || !(chunk & TAGMASK)) return 0;
    chunk &= TAGMASK;
    for (int item = 24; item >= 16; item -= 8)
        if (ue_index(chunk, 0, item) == 0 && ue_index(chunk, 1, item) == 1 && ue_index(chunk, 2, item) == 2) { ue_item = item; return 1; }
    return 0;
}

// "ue BLOCO0 filtro": objetos cujo nome ou classe contém o filtro (vazio ou * = só conta)
static void cmd_ue(const char *block0, const char *filter) {
    if (mods_pid != target) { psize = ptr_size(); if (load_modules()) { puts("err sem mapa"); return; } }
    if (psize != 8) { puts("err só jogos de 64 bits"); return; }
    uint64_t b0 = strtoull(block0, NULL, 16);
    if (b0 < 0x10000) { puts("err endereço do bloco de nomes inválido"); return; }
    uint64_t tab = scan_qwords(is_blocks, &b0);
    if (!tab) { puts("err não achei a tabela dos blocos de nomes"); return; }
    ue_nblocks = 0;
    while (ue_nblocks < 8192 && !read_mem(tab + ue_nblocks * 8ULL, &ue_blocks[ue_nblocks], 8) && ue_blocks[ue_nblocks]) ue_blocks[ue_nblocks] &= TAGMASK, ue_nblocks++;
    uint64_t arr = scan_qwords(is_objects, NULL);
    if (!arr) { printf("err achei %d blocos de nomes, mas não a lista de objetos\n", ue_nblocks); return; }
    uint64_t objs;
    int32_t num;
    read_mem(arr, &objs, 8);
    read_mem(arr + 20, &num, 4);
    objs &= TAGMASK;
    const char *f = !filter[0] || !strcmp(filter, "*") ? NULL : filter;
    int shown = 0, total = 0;
    uint64_t chunk = 0;
    int cur = -1;
    char name[160], cname[160];
    for (int i = 0; i < num && f; i++) {
        if (i / 65536 != cur) { cur = i / 65536; if (read_mem(objs + cur * 8ULL, &chunk, 8)) break; chunk &= TAGMASK; }
        uint64_t obj, cls;
        uint32_t ni, ci;
        if (read_mem(chunk + (uint64_t)(i % 65536) * ue_item, &obj, 8) || !(obj &= TAGMASK)) continue;
        if (read_mem(obj + 0x18, &ni, 4) || read_mem(obj + 0x10, &cls, 8) || ue_name(ni, name, sizeof name)) continue;
        cls &= TAGMASK;
        if (!cls || read_mem(cls + 0x18, &ci, 4) || ue_name(ci, cname, sizeof cname)) strcpy(cname, "?");
        if (!strcasestr(name, f) && !strcasestr(cname, f)) continue;
        total++;
        if (shown++ < 200) printf("ueobj %" PRIx64 " %s %s\n", obj, cname, name);
    }
    printf("ok %d %d %d\n", total, ue_nblocks, num);
}

static void cmd_func(const char *addr) {
    uint64_t a = strtoull(addr, NULL, 16), s, e;
    char name[160], w[160];
    int mach;
    if (func_of(a, &s, &e, name, sizeof name, &mach) || where_of(s, w, sizeof w)) { puts("err fora das funções das libs do jogo"); return; }
    printf("ok %" PRIx64 " %" PRIx64 " %s %s %s\n", s, e, w, mach == EM_X86_64 ? "x86_64" : mach == EM_AARCH64 ? "arm64" : "outra", name[0] ? name : "-");
}

// "calls ADDR": as funções que a função de ADDR chama (BL no ARM64, CALL rel32 no x86_64), com nome quando tem
static void cmd_calls(const char *addr) {
    uint64_t a = strtoull(addr, NULL, 16), s, e;
    char name[160];
    int mach;
    if (func_of(a, &s, &e, name, sizeof name, &mach)) { puts("err fora das funções das libs do jogo"); return; }
    size_t len = e - s > 0x10000 ? 0x10000 : e - s;
    unsigned char *b = malloc(len + 8);
    if (!b || read_mem(s, b, len)) { free(b); puts("err não leu o código"); return; }
    elf_t el;
    uint64_t *st = NULL, seen[100];
    fsym_t *fs = NULL;
    size_t nst = 0, nf = 0, ns = 0;
    int m = game_module(s);
    if (m >= 0 && !elf_open(&mods[m], &el)) { nst = elf_starts(&el, &st); nf = elf_funcs(&el, &fs); }
    for (size_t i = 0; i + 4 <= len && ns < 100; i += mach == EM_AARCH64 ? 4 : 1) {
        uint64_t to = 0;
        if (mach == EM_AARCH64) {
            uint32_t w;
            memcpy(&w, b + i, 4);
            if ((w & 0xfc000000u) == 0x94000000u) to = s + i + (uint64_t)((int64_t)((int32_t)(w << 6) >> 6) * 4);
        } else if (mach == EM_X86_64 && b[i] == 0xe8 && i + 5 <= len) {
            int32_t r;
            memcpy(&r, b + i + 1, 4);
            to = s + i + 5 + (int64_t)r;
        }
        if (!to) continue;
        // x86 lido byte a byte acha "E8" no meio de outra instrução: só vale se cair no começo de uma função
        size_t lo = 0, hi = nst;
        while (lo < hi) { size_t mid = (lo + hi) / 2; if (st[mid] < to) lo = mid + 1; else hi = mid; }
        int start = lo < nst && st[lo] == to;
        char tn[160] = "";
        lo = 0; hi = nf;
        while (lo < hi) { size_t mid = (lo + hi) / 2; if (fs[mid].a < to) lo = mid + 1; else hi = mid; }
        if (lo < nf && fs[lo].a == to) { start = 1; elf_name(&el, fs[lo].name, tn, sizeof tn); }
        if (!start) continue;
        int dup = 0;
        for (size_t k = 0; k < ns; k++) dup |= seen[k] == to;
        if (dup) continue;
        seen[ns++] = to;
        printf("call %" PRIx64 " %" PRIx64 " %s\n", s + i, to, tn[0] ? tn : "-");
    }
    free(b);
    free(st);
    free(fs);
    printf("ok %zu\n", ns);
}

// "O que escreve aqui", como o "Find out what writes to this address" do Cheat Engine: um ponto de observação de
// hardware em cada thread do jogo por alguns segundos; cada instrução que escreve no endereço é anotada e o jogo segue.
// exec = 0: ponto de observação (escrita em addr); exec = 1: ponto de parada (a instrução em addr vai rodar)
static int hw_point(pid_t tid, uint64_t addr, int on, int exec) {
#if defined(__x86_64__)
    if (on && ptrace(PTRACE_POKEUSER, tid, offsetof(struct user, u_debugreg[0]), (void *)(uintptr_t)addr)) return -1;
    ptrace(PTRACE_POKEUSER, tid, offsetof(struct user, u_debugreg[6]), 0);
    // DR0 ligado; escrita de 4 bytes, ou execução (RW=00, LEN=00)
    unsigned long dr7 = on ? (exec ? 1UL : (1UL | (1UL << 16) | (3UL << 18))) : 0;
    return ptrace(PTRACE_POKEUSER, tid, offsetof(struct user, u_debugreg[7]), (void *)dr7) ? -1 : 0;
#elif defined(__aarch64__)
    struct user_hwdebug_state st;
    memset(&st, 0, sizeof st);
    st.dbg_regs[0].addr = exec ? addr : addr & ~7ULL;
    // ligado, só no app (EL0); escrita dos 4 bytes do valor dentro da palavra de 8, ou a instrução A64 inteira
    st.dbg_regs[0].ctrl = !on ? 0 : exec ? (1u | (2u << 1) | (0xfu << 5)) : (1u | (2u << 1) | (2u << 3) | ((0xfu << (addr & 7)) << 5));
    struct iovec io = {&st, offsetof(struct user_hwdebug_state, dbg_regs) + sizeof st.dbg_regs[0]};
    return ptrace(PTRACE_SETREGSET, tid, exec ? NT_ARM_HW_BREAK : NT_ARM_HW_WATCH, &io) ? -1 : 0;
#else
    (void)tid; (void)addr; (void)on; (void)exec;
    errno = ENOSYS;
    return -1;
#endif
}


// quem chamou: o endereço de volta (arm64: registrador x30; x86: o topo da pilha na entrada da função)
static uint64_t thread_caller(pid_t tid) {
#if defined(__x86_64__)
    struct user_regs_struct r;
    if (ptrace(PTRACE_GETREGS, tid, 0, &r)) return 0;
    errno = 0;
    long v = ptrace(PTRACE_PEEKDATA, tid, (void *)(uintptr_t)r.rsp, 0);
    return errno ? 0 : (uint64_t)v;
#elif defined(__aarch64__)
    struct user_pt_regs r;
    struct iovec io = {&r, sizeof r};
    return ptrace(PTRACE_GETREGSET, tid, NT_PRSTATUS, &io) ? 0 : r.regs[30];
#else
    (void)tid;
    return 0;
#endif
}

static uint64_t thread_pc(pid_t tid) {
#if defined(__x86_64__)
    struct user_regs_struct r;
    return ptrace(PTRACE_GETREGS, tid, 0, &r) ? 0 : r.rip;  // x86: já passou da instrução (o PC é o da seguinte)
#elif defined(__aarch64__)
    struct user_pt_regs r;
    struct iovec io = {&r, sizeof r};
    return ptrace(PTRACE_GETREGSET, tid, NT_PRSTATUS, &io) ? 0 : r.pc;  // arm64: a própria instrução, ainda não rodou
#else
    (void)tid;
    return 0;
#endif
}

#define WATCH_TIDS 1024
static double now_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

// Uma sessão de observação (exec = 0: quem escreve em addr) ou de parada (exec = 1: quantas vezes a instrução em addr roda
// e quem chamou). Pega cada thread um instante, liga o ponto, solta; no fim desliga tudo e solta o jogo.
static void hw_session(uint64_t a, int sec, int exec) {
    static pid_t tids[WATCH_TIDS];
    static char held[WATCH_TIDS];
    int nt = 0, armed = 0, why = 0;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", target);
    DIR *dir = opendir(path);
    if (!dir) { puts("err não leu as threads do jogo"); return; }
    for (struct dirent *e; (e = readdir(dir)) && nt < WATCH_TIDS;) if (atoi(e->d_name) > 0) tids[nt++] = atoi(e->d_name);
    closedir(dir);
    for (int i = 0; i < nt; i++) {
        held[i] = 0;
        if (ptrace(PTRACE_SEIZE, tids[i], 0, 0)) { why = errno; continue; }
        held[i] = 1;
        int st;
        if (ptrace(PTRACE_INTERRUPT, tids[i], 0, 0) || waitpid(tids[i], &st, __WALL) != tids[i]) continue;
        if (!hw_point(tids[i], a, 1, exec)) armed++;
        else why = errno;
        ptrace(PTRACE_CONT, tids[i], 0, 0);
    }
    struct { uint64_t pc; int n; } hit[32];
    int nh = 0, calls = 0;
    double end = now_s() + sec;
    while (armed && now_s() < end) {
        int st;
        pid_t t = waitpid(-1, &st, __WALL | WNOHANG);
        if (t <= 0) { usleep(2000); continue; }
        int k = 0;
        while (k < nt && tids[k] != t) k++;
        if (k == nt) continue;
        if (WIFEXITED(st) || WIFSIGNALED(st)) { held[k] = 0; continue; }
        if (!WIFSTOPPED(st)) continue;
        int sig = WSTOPSIG(st), event = st >> 16;
        if (sig == SIGTRAP && !event) {
            uint64_t pc = exec ? thread_caller(t) : thread_pc(t);
            calls++;
            int j = 0;
            while (j < nh && hit[j].pc != pc) j++;
            if (j == nh && nh < 32) hit[nh++] = (typeof(hit[0])){pc, 0};
            if (j < nh) hit[j].n++;
#if defined(__x86_64__)
            if (!exec) { hw_point(t, a, 1, 0); ptrace(PTRACE_CONT, t, 0, 0); continue; }  // x86: a escrita já aconteceu
#endif
            // a instrução ainda não rodou: desliga, deixa ela rodar sozinha e liga de novo
            hw_point(t, a, 0, exec);
            if (!ptrace(PTRACE_SINGLESTEP, t, 0, 0)) waitpid(t, &st, __WALL);
            hw_point(t, a, 1, exec);
            ptrace(PTRACE_CONT, t, 0, 0);
        } else if (event == PTRACE_EVENT_STOP) {
            ptrace(PTRACE_CONT, t, 0, 0);
        } else {
            ptrace(PTRACE_CONT, t, 0, sig);  // sinal do próprio jogo: entrega a ele
        }
    }
    for (int i = 0; i < nt; i++) {
        if (!held[i]) continue;
        int st;
        if (!ptrace(PTRACE_INTERRUPT, tids[i], 0, 0) && waitpid(tids[i], &st, __WALL) == tids[i]) hw_point(tids[i], a, 0, exec);
        ptrace(PTRACE_DETACH, tids[i], 0, 0);
    }
    if (!armed) { printf("err o sistema não deixou %s (%s)\n", exec ? "pôr o ponto de parada" : "observar o endereço", strerror(why ? why : ENOSYS)); return; }
    if (exec) printf("calls %d\n", calls);
    for (int j = 0; j < nh; j++) {
        char w[160];
        if (where_of(hit[j].pc, w, sizeof w)) snprintf(w, sizeof w, "?");
        printf("%s %" PRIx64 " %d %s\n", exec ? "caller" : "pc", hit[j].pc, hit[j].n, w);
    }
    printf("ok %d\n", exec ? calls : nh);
}

static void cmd_watch(const char *addr, const char *secs) {
    uint64_t a = strtoull(addr, NULL, 16);
    int sec = atoi(secs);
    if (sec < 1 || sec > 60) sec = 15;
    if (a % 4) { puts("err o endereço precisa ser múltiplo de 4"); return; }
    hw_session(a, sec, 0);
}

// Contador de chamadas, como o Tracer das ferramentas de IL2CPP: quantas vezes o método (o código em addr) roda e quem chama
static void cmd_trace(const char *addr, const char *secs) {
    uint64_t a = strtoull(addr, NULL, 16);
    int sec = atoi(secs);
    if (sec < 1 || sec > 60) sec = 15;
    hw_session(a, sec, 1);
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
    // code = todo o código; xa = só o do jogo; xs = só o do sistema (as regiões Xa e Xs do GameGuardian)
    region_exec = !strcmp(mode, "code") || !strcmp(mode, "xa") || !strcmp(mode, "xs");
    exec_only = !strcmp(mode, "xa") ? 1 : !strcmp(mode, "xs") ? 2 : 0;
    p->cs.want = keep && !region_exec ? ranges : ~(1u << R_V);
    int r = each_region(add_chunks, &p->cs);
    region_exec = 0;
    exec_only = 0;
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
    p->cs.want = ranges_default;
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
// Roda um programa (sem shell no meio) e espera; a saída vai para o nada. Devolve o código de saída (127: não existe).
static int run_quiet(char *const argv[]) {
    fflush(stdout);
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int nul = open("/dev/null", O_WRONLY);
        if (nul >= 0) { dup2(nul, 1); dup2(nul, 2); }
        execvp(argv[0], argv);
        _exit(127);
    }
    int st = 0;
    waitpid(pid, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

// Backup do save do jogo: a pasta de dados do app (/data/data/pacote, sem cache) vira um .tar guardado pelo Tinker.
// Antes de experimentar (Foto do jogo, patch), um backup deixa voltar o progresso se algo der errado.
// a pasta de dados de PKG: no Android 11+ o daemon (aberto pelo Tinker) herda o isolamento do app e /data/data só
// mostra a pasta do próprio Tinker; a pasta real de cada app continua em /data_mirror/data_ce/null/0
static const char *data_dir_of(const char *pkg) {
    const char *d = getenv("TINKER_DATA_DIR");
    if (d) return d;
    // /proc/1/root: a visão do init (sem o isolamento), caso o aparelho não tenha o /data_mirror
    static const char *bases[] = {"/data/data", "/data_mirror/data_ce/null/0", "/proc/1/root/data/data", "/data/user/0"};
    char p[600];
    struct stat st;
    for (size_t i = 0; i < sizeof bases / sizeof *bases; i++) {
        snprintf(p, sizeof p, "%s/%s", bases[i], pkg);
        if (!stat(p, &st) && S_ISDIR(st.st_mode)) return bases[i];
    }
    return bases[0];
}
// a pasta externa do jogo (Android/data/PKG) pelo caminho real: /sdcard passa por FUSE e o root nem sempre enxerga
static const char *ext_dir(void) { const char *d = getenv("TINKER_EXT_DIR"); return d ? d : "/data/media/0/Android/data"; }
static const char *saves_dir(void) { const char *d = getenv("TINKER_SAVES_DIR"); return d ? d : "/data/local/tmp/tinker-saves"; }

static int valid_pkg(const char *p) {
    if (!*p || strlen(p) > 120 || strstr(p, "..")) return 0;
    for (; *p; p++) if (!(isalnum((unsigned char)*p) || *p == '.' || *p == '_')) return 0;
    return 1;
}

static void cmd_backup(const char *pkg) {
    if (!valid_pkg(pkg)) { puts("err pacote inválido"); return; }
    char src[512], dir[512], file[600], name[64];
    snprintf(src, sizeof src, "%s/%s", data_dir_of(pkg), pkg);
    struct stat st;
    if (stat(src, &st) || !S_ISDIR(st.st_mode)) { printf("err sem pasta de dados: %s\n", src); return; }
    snprintf(dir, sizeof dir, "%s/%s", saves_dir(), pkg);
    char *mk[] = {"mkdir", "-p", dir, NULL};
    if (run_quiet(mk)) { printf("err não criou %s\n", dir); return; }
    time_t now = time(NULL);
    strftime(name, sizeof name, "%Y%m%d-%H%M%S.tar", localtime(&now));
    snprintf(file, sizeof file, "%s/%s", dir, name);
    char exc1[200], exc2[200];
    snprintf(exc1, sizeof exc1, "--exclude=%s/cache", pkg);
    snprintf(exc2, sizeof exc2, "--exclude=%s/code_cache", pkg);
    char *tar[] = {"tar", "-cf", file, exc1, exc2, "-C", (char *)data_dir_of(pkg), (char *)pkg, NULL};
    int r = run_quiet(tar);
    if (r && stat(file, &st)) { printf("err o tar falhou (%d)\n", r); return; }
    // a pasta externa vai num tar irmão (NOME-ext.tar): volta junto com o principal
    char ext[512], efile[640];
    snprintf(ext, sizeof ext, "%s/%s", ext_dir(), pkg);
    if (!stat(ext, &st) && S_ISDIR(st.st_mode)) {
        snprintf(efile, sizeof efile, "%s/%.*s-ext.tar", dir, (int)(strlen(name) - 4), name);
        char *etar[] = {"tar", "-cf", efile, exc1, exc2, "-C", (char *)ext_dir(), (char *)pkg, NULL};
        run_quiet(etar);
    }
    stat(file, &st);
    printf("ok %s %lld\n", name, (long long)st.st_size);
}

// ---- editor de save: lista os arquivos de progresso do jogo, copia um para o app ler e copia de volta o editado ----
// Só dentro da pasta de dados do jogo; o caminho não sai dela (sem "..") e o destino/origem é a pasta do próprio Tinker.
static int valid_rel(const char *r) {
    if (!*r || *r == '/' || strstr(r, "..") || strlen(r) > 120) return 0;
    for (; *r; r++) if (!(isalnum((unsigned char)*r) || strchr("._-/+@", *r))) return 0;
    return 1;
}

// REL do editor de save: "ext/..." é a pasta externa do jogo (Android/data/PKG); o resto, a interna (/data/data/PKG)
static void save_path(char *out, size_t n, const char *pkg, const char *rel) {
    if (!strncmp(rel, "ext/", 4)) snprintf(out, n, "%s/%s/%s", ext_dir(), pkg, rel + 4);
    else snprintf(out, n, "%s/%s/%s", data_dir_of(pkg), pkg, rel);
}

static int tinker_path(const char *p) {
    const char *t = getenv("TINKER_APP_DIR");
    if (t) return !strncmp(p, t, strlen(t)) && !strstr(p, "..");
    return (!strncmp(p, "/data/data/dev.tinker.app/", 26) || !strncmp(p, "/data/user/0/dev.tinker.app/", 28)) && !strstr(p, "..");
}

static void list_saves(const char *root, const char *pfx, const char *rel, int depth, int *n) {
    char path[700];
    snprintf(path, sizeof path, "%s/%s", root, rel);
    DIR *d = opendir(path);
    for (struct dirent *e; d && (e = readdir(d)) && *n < 300;) {
        if (e->d_name[0] == '.') continue;
        char r[400], f[1100];
        snprintf(r, sizeof r, "%s%s%s", rel, *rel ? "/" : "", e->d_name);
        snprintf(f, sizeof f, "%s/%s", root, r);
        struct stat st;
        if (lstat(f, &st)) continue;
        if (S_ISDIR(st.st_mode)) {
            // cache, código e bibliotecas não são save
            if (depth < 3 && strcmp(e->d_name, "cache") && strcmp(e->d_name, "code_cache") && strcmp(e->d_name, "lib") && strcmp(e->d_name, "app_webview")
                && strcmp(e->d_name, "il2cpp") && strcmp(e->d_name, "UnityCache"))
                list_saves(root, pfx, r, depth + 1, n);
        } else if (S_ISREG(st.st_mode) && st.st_size > 0 && st.st_size <= (4 << 20) && valid_rel(r)) {
            printf("file %s%s %lld\n", pfx, r, (long long)st.st_size);
            (*n)++;
        }
    }
    if (d) closedir(d);
}

static void cmd_savefiles(const char *pkg) {
    if (!valid_pkg(pkg)) { puts("err pacote inválido"); return; }
    char root[512];
    snprintf(root, sizeof root, "%s/%s", data_dir_of(pkg), pkg);
    int n = 0;
    list_saves(root, "", "", 0, &n);
    // muitos jogos (Unity, Godot) guardam o progresso na pasta externa: aparece como ext/...
    snprintf(root, sizeof root, "%s/%s", ext_dir(), pkg);
    list_saves(root, "ext/", "", 0, &n);
    printf("ok %d\n", n);
}

static int copy_file(const char *from, const char *to) {
    FILE *a = fopen(from, "rb");
    if (!a) return -1;
    char tmp[700];
    snprintf(tmp, sizeof tmp, "%s.tinker-tmp", to);
    FILE *b = fopen(tmp, "wb");
    if (!b) { fclose(a); return -1; }
    char buf[65536];
    size_t n;
    int bad = 0;
    while ((n = fread(buf, 1, sizeof buf, a)) > 0) if (fwrite(buf, 1, n, b) != n) bad = 1;
    fclose(a);
    if (fclose(b) || bad) { unlink(tmp); return -1; }
    return rename(tmp, to);
}

// readsave PKG REL DEST: o arquivo do jogo vai para DEST (na pasta do Tinker), legível pelo app
static void cmd_readsave(const char *pkg, const char *rel, const char *dest) {
    if (!valid_pkg(pkg) || !valid_rel(rel) || !tinker_path(dest)) { puts("err caminho inválido"); return; }
    char src[700];
    save_path(src, sizeof src, pkg, rel);
    if (copy_file(src, dest)) { printf("err não copiou: %s\n", strerror(errno)); return; }
    chmod(dest, 0644);
    puts("ok");
}

// writesave PKG REL SRC: o editado (SRC, na pasta do Tinker) volta para o jogo com o mesmo dono, permissão e contexto
static void cmd_writesave(const char *pkg, const char *rel, const char *srcp) {
    if (!valid_pkg(pkg) || !valid_rel(rel) || !tinker_path(srcp)) { puts("err caminho inválido"); return; }
    char dst[700];
    save_path(dst, sizeof dst, pkg, rel);
    struct stat st;
    if (stat(dst, &st)) { printf("err o arquivo do jogo não existe: %s\n", rel); return; }
    if (copy_file(srcp, dst)) { printf("err não gravou: %s\n", strerror(errno)); return; }
    if (chown(dst, st.st_uid, st.st_gid)) { /* teste sem root: segue */ }
    chmod(dst, st.st_mode & 07777);
    char *rc[] = {"restorecon", dst, NULL};
    run_quiet(rc);
    puts("ok");
}

static void cmd_backups(const char *pkg) {
    if (!valid_pkg(pkg)) { puts("err pacote inválido"); return; }
    char dir[512];
    snprintf(dir, sizeof dir, "%s/%s", saves_dir(), pkg);
    DIR *d = opendir(dir);
    int n = 0;
    for (struct dirent *e; d && (e = readdir(d));) {
        size_t L = strlen(e->d_name);
        if (L < 5 || strcmp(e->d_name + L - 4, ".tar") || (L > 8 && !strcmp(e->d_name + L - 8, "-ext.tar"))) continue;
        char f[800];
        struct stat st;
        snprintf(f, sizeof f, "%s/%s", dir, e->d_name);
        if (!stat(f, &st)) { printf("save %s %lld\n", e->d_name, (long long)st.st_size); n++; }
    }
    if (d) closedir(d);
    printf("ok %d\n", n);
}

// Volta um backup: fecha o jogo, devolve os arquivos, o dono (o usuário do app) e o contexto do SELinux.
static void cmd_restoresave(const char *pkg, const char *name) {
    if (!valid_pkg(pkg)) { puts("err pacote inválido"); return; }
    size_t L = strlen(name);
    if (L < 5 || L > 60 || strcmp(name + L - 4, ".tar") || strchr(name, '/') || strstr(name, "..")) { puts("err backup inválido"); return; }
    char file[800], dst[512], own[64];
    snprintf(file, sizeof file, "%s/%s/%s", saves_dir(), pkg, name);
    snprintf(dst, sizeof dst, "%s/%s", data_dir_of(pkg), pkg);
    struct stat st, ds;
    if (stat(file, &st)) { puts("err esse backup não existe"); return; }
    if (stat(dst, &ds)) { printf("err sem pasta de dados: %s\n", dst); return; }
    char *stop[] = {"am", "force-stop", (char *)pkg, NULL};
    run_quiet(stop);  // jogo aberto escreveria o save de novo por cima
    char *tar[] = {"tar", "-xf", file, "-C", (char *)data_dir_of(pkg), NULL};
    int r = run_quiet(tar);
    if (r) { printf("err o tar falhou (%d)\n", r); return; }
    snprintf(own, sizeof own, "%u:%u", (unsigned)ds.st_uid, (unsigned)ds.st_gid);
    char *ch[] = {"chown", "-R", own, dst, NULL};
    run_quiet(ch);
    char *rc[] = {"restorecon", "-R", dst, NULL};
    run_quiet(rc);  // fora do Android não existe: tudo bem
    // e a pasta externa, se o backup tem ela
    char efile[860], edst[512];
    snprintf(efile, sizeof efile, "%s/%s/%.*s-ext.tar", saves_dir(), pkg, (int)(L - 4), name);
    snprintf(edst, sizeof edst, "%s/%s", ext_dir(), pkg);
    if (!stat(efile, &st) && !stat(edst, &ds)) {
        char *etar[] = {"tar", "-xf", efile, "-C", (char *)ext_dir(), NULL};
        if (run_quiet(etar)) { puts("err o tar da pasta externa falhou"); return; }
        snprintf(own, sizeof own, "%u:%u", (unsigned)ds.st_uid, (unsigned)ds.st_gid);
        char *ech[] = {"chown", "-R", own, edst, NULL};
        run_quiet(ech);
        char *erc[] = {"restorecon", "-R", edst, NULL};
        run_quiet(erc);
    }
    puts("ok");
}

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
    static char line[24576];  // grande: "photowin" leva até 512 faixas (lidas da linha, não do a1)
    char cmd[32], a1[400], a2[128], a3[128], a4[128];
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
        if (!strcmp(cmd, "canattach")) { cmd_canattach(); continue; }
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
            drop_photo();  // a foto era do jogo anterior
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
        // backup do save não precisa do jogo aberto (até melhor fechado)
        if (!strcmp(cmd, "backup")) { cmd_backup(a1); continue; }
        if (!strcmp(cmd, "backups")) { cmd_backups(a1); continue; }
        if (!strcmp(cmd, "savefiles")) { cmd_savefiles(a1); continue; }
        if (!strcmp(cmd, "readsave")) { cmd_readsave(a1, a2, a3); continue; }
        if (!strcmp(cmd, "writesave")) { cmd_writesave(a1, a2, a3); continue; }
        if (!strcmp(cmd, "restoresave")) { cmd_restoresave(a1, a2); continue; }
        if (!target) { puts("err nenhum jogo escolhido"); continue; }
        if (!alive()) { puts("err o jogo fechou"); continue; }
        if (!strcmp(cmd, "regions")) cmd_regions();
        else if (!strcmp(cmd, "ranges")) puts(set_ranges(a1) ? "ok" : "err região inválida");
        else if (!strcmp(cmd, "rangesdefault")) {
            if (!strcmp(a1, "all")) { ranges_default = ~(1u << R_V); ranges = ranges_default; puts("ok"); }
            else if (set_ranges(a1)) { ranges_default = ranges; puts("ok"); } else puts("err região inválida");
        }
        else if (!strcmp(cmd, "photowin")) { line[strcspn(line, "\r\n")] = 0; cmd_photowin(*a1 ? strstr(line, a1) : NULL); }
        else if (!strcmp(cmd, "search")) cmd_search(a1, a2);
        else if (!strcmp(cmd, "group")) cmd_group(a1, a2, a3);
        else if (!strcmp(cmd, "refine")) cmd_compare("eq", a1);
        else if (!strcmp(cmd, "compare")) cmd_compare(a1, a2);
        else if (!strcmp(cmd, "addrend")) cmd_addrend(a1);
        else if (!strcmp(cmd, "snapshot")) cmd_snapshot(a1);
        else if (!strcmp(cmd, "pointers")) cmd_pointers(a1, a2, a3, a4[0] == '1');
        else if (!strcmp(cmd, "aob")) cmd_aob(a1, a2, 1);
        else if (!strcmp(cmd, "aobfind")) cmd_aob(a1, a2[0] ? a2 : "data", 0);
        else if (!strcmp(cmd, "il2cpp")) cmd_il2cpp(a1);
        else if (!strcmp(cmd, "il2objs")) cmd_il2objs(a1);
        else if (!strcmp(cmd, "il2classes")) cmd_il2classes();
        else if (!strcmp(cmd, "resolve")) cmd_resolve(strstr(line, "resolve") + 7);
        else if (!strcmp(cmd, "where")) cmd_where(a1);
        else if (!strcmp(cmd, "syms")) cmd_syms(a1);
        else if (!strcmp(cmd, "func")) cmd_func(a1);
        else if (!strcmp(cmd, "calls")) cmd_calls(a1);
        else if (!strcmp(cmd, "ue")) cmd_ue(a1, a2);
        else if (!strcmp(cmd, "photo")) cmd_photo();
        else if (!strcmp(cmd, "photonoise")) cmd_photonoise();
        else if (!strcmp(cmd, "photodiff")) cmd_photodiff();
        else if (!strcmp(cmd, "photonoise2")) cmd_photonoise2();
        else if (!strcmp(cmd, "photostep")) cmd_photostep(*a1 ? strtoul(a1, NULL, 10) : 20000);
        else if (!strcmp(cmd, "photorestore")) cmd_photorestore(strcmp(a1, "except") ? a1 : strstr(line, "except"));
        else if (!strcmp(cmd, "photodrop")) { drop_photo(); puts("ok"); }
        else if (!strcmp(cmd, "activity")) cmd_activity(a1);
        else if (!strcmp(cmd, "selftestdata")) cmd_selftestdata();
        else if (!strcmp(cmd, "watch")) cmd_watch(a1, a2);
        else if (!strcmp(cmd, "trace")) cmd_trace(a1, a2);
        else if (!strcmp(cmd, "il2methods")) cmd_il2methods(a1);
        else if (!strcmp(cmd, "luatable")) cmd_luatable(a1);
        else if (!strcmp(cmd, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(cmd, "read")) cmd_read(a1, a2);
        else if (!strcmp(cmd, "dump")) cmd_dump(a1, a2, a3);
        else if (!strcmp(cmd, "write")) cmd_write(a1, a2, a3);
        else if (!strcmp(cmd, "writeall")) cmd_writeall(a1);
        else if (!strcmp(cmd, "freeze")) cmd_freeze(a1, a2, a3, a4);
        else if (!strcmp(cmd, "unfreeze")) cmd_unfreeze(a1, a2);
        else if (!strcmp(cmd, "frozen")) cmd_frozen();
        else if (!strcmp(cmd, "pause")) puts(kill(target, SIGSTOP) ? "err não pausou" : "ok");
        else if (!strcmp(cmd, "resume")) puts(kill(target, SIGCONT) ? "err não continuou" : "ok");
        else if (!strcmp(cmd, "kill")) puts(kill(target, SIGKILL) ? "err não fechou" : "ok");
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
