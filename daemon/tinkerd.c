// tinkerd: daemon de memória do Tinker. Roda como root e fala por stdin/stdout,
// um comando por linha; cada resposta termina com "ok ..." ou "err ...".
// Linhas de texto livre (ex.: crashlog) começam com "| " para não confundir o protocolo.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
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
static int memfd = -1;

typedef struct { uint64_t addr; uint8_t type, region; } hit_t;
// shortcut: resultados num vetor em RAM (16 bytes cada), trocar por arquivo mmap se passar de dezenas de milhões
static hit_t *res;
static size_t nres, cap;

typedef struct { uint64_t raw; double f; } value_t;

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

static int find_type(const char *s) {
    for (int i = 0; i < NTYPES; i++) if (!strcmp(s, TYPES[i].name)) return i;
    return -1;
}

static int parse_value(int t, const char *s, value_t *v) {
    char *end;
    if (TYPES[t].isfloat) {
        v->f = strtod(s, &end);
    } else {
        double d = strtod(s, &end);
        if (end == s || (*end && *end != '\n') || d != floor(d)) return 0;  // "1.5" não é inteiro
        v->raw = (uint64_t)strtoll(s, &end, 0);
        int bits = TYPES[t].size * 8;
        if (bits < 64) v->raw &= (1ULL << bits) - 1;  // -1 e 4294967295 viram o mesmo dword
    }
    return end != s && (*end == 0 || *end == '\n');
}

static int matches(int t, const unsigned char *p, const value_t *v) {
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
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        char *name = off ? line + off : line + strlen(line);
        name[strcspn(name, "\n")] = 0;
        fn(s, e, region_of(perms, name), ctx);
    }
    fclose(f);
    return 0;
}

static int alive(void) { return target > 0 && !(kill(target, 0) && errno == ESRCH); }

static void push(uint64_t a, int t, int region) {
    if (nres == cap) {
        cap = cap ? cap * 2 : 4096;
        res = realloc(res, cap * sizeof *res);
        if (!res) { puts("err sem memória"); exit(1); }
    }
    res[nres++] = (hit_t){a, (uint8_t)t, (uint8_t)region};
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

typedef struct { int types[NTYPES], ntypes, step; value_t v[NTYPES]; unsigned char *buf; } scan_t;

static void scan_region(uint64_t s, uint64_t e, int region, void *ctx) {
    scan_t *sc = ctx;
    if (!(ranges & (1u << region))) return;
    const size_t CHUNK = 1 << 20;
    for (uint64_t a = s; a < e; a += CHUNK) {
        size_t n = e - a < CHUNK ? e - a : CHUNK;
        if (read_mem(a, sc->buf, n)) continue;
        // um resultado por endereço: no Auto, Dword/Float ganham de Qword/Double,
        // senão o Qword no mesmo lugar sobrescreveria os 4 bytes vizinhos ao editar
        for (size_t i = 0; i + sc->step <= n; i += sc->step)
            for (int k = 0; k < sc->ntypes; k++) {
                int t = sc->types[k], sz = TYPES[t].size;
                if (i % sz || i + sz > n) continue;
                if (matches(t, sc->buf + i, &sc->v[t])) { push(a + i, t, region); break; }
            }
    }
}

static void cmd_search(const char *tname, const char *value) {
    scan_t sc = {.ntypes = 0};
    if (!strcmp(tname, "auto")) {
        // como o "Auto" do GG: o mesmo número em Dword, Float, Qword e Double
        int all[] = {T_I32, T_F32, T_I64, T_F64};
        for (int i = 0; i < 4; i++) if (parse_value(all[i], value, &sc.v[all[i]])) sc.types[sc.ntypes++] = all[i];
    } else {
        int t = find_type(tname);
        if (t < 0) { printf("err tipo inválido: %s\n", tname); return; }
        if (parse_value(t, value, &sc.v[t])) sc.types[sc.ntypes++] = t;
    }
    if (!sc.ntypes) { printf("err valor inválido: %s\n", value); return; }
    sc.step = 8;
    for (int k = 0; k < sc.ntypes; k++) if (TYPES[sc.types[k]].size < sc.step) sc.step = TYPES[sc.types[k]].size;
    sc.buf = malloc(1 << 20);
    nres = 0;
    int r = each_region(scan_region, &sc);
    free(sc.buf);
    if (r) puts("err não leu o mapa de memória");
    else printf("ok %zu\n", nres);
}

static void cmd_refine(const char *value) {
    value_t v[NTYPES];
    int ok[NTYPES];
    for (int t = 0; t < NTYPES; t++) ok[t] = parse_value(t, value, &v[t]);
    // shortcut: uma leitura por resultado, lento só se o refino começar com milhões
    size_t k = 0;
    unsigned char b[8];
    for (size_t i = 0; i < nres; i++) {
        int t = res[i].type;
        if (ok[t] && !read_mem(res[i].addr, b, TYPES[t].size) && matches(t, b, &v[t])) res[k++] = res[i];
    }
    nres = k;
    printf("ok %zu\n", nres);
}

static void cmd_list(size_t n) {
    unsigned char b[8];
    char out[64];
    for (size_t i = 0; i < nres && i < n; i++) {
        int t = res[i].type;
        if (read_mem(res[i].addr, b, TYPES[t].size)) strcpy(out, "?");
        else format(t, b, out);
        printf("%" PRIx64 " %s %s %s\n", res[i].addr, TYPES[t].name, REGIONS[res[i].region], out);
    }
    printf("ok %zu\n", nres);
}

static void cmd_read(const char *addr, const char *tname) {
    int t = find_type(tname);
    unsigned char b[8];
    char out[64];
    if (t < 0) { printf("err tipo inválido: %s\n", tname); return; }
    if (read_mem(strtoull(addr, NULL, 16), b, TYPES[t].size)) { printf("err não leu %s\n", addr); return; }
    format(t, b, out);
    printf("ok %s\n", out);
}

static void cmd_write(const char *addr, const char *tname, const char *value) {
    int t = find_type(tname);
    value_t v;
    unsigned char b[8];
    if (t < 0) { printf("err tipo inválido: %s\n", tname); return; }
    if (!parse_value(t, value, &v)) { printf("err valor inválido: %s\n", value); return; }
    encode(t, &v, b);
    if (write_mem(strtoull(addr, NULL, 16), b, TYPES[t].size)) printf("err falhou ao escrever: %s\n", strerror(errno));
    else puts("ok");
}

static void cmd_writeall(const char *value) {
    size_t fail = 0, skip = 0;
    for (size_t i = 0; i < nres; i++) {
        int t = res[i].type;
        value_t v;
        unsigned char b[8];
        if (!parse_value(t, value, &v)) { skip++; continue; }  // ex.: "1.5" não vai para Dword
        encode(t, &v, b);
        fail += write_mem(res[i].addr, b, TYPES[t].size) != 0;
    }
    printf("ok %zu %zu\n", nres - fail - skip, fail + skip);
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

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    char line[512], cmd[32], a1[128], a2[128], a3[128];
    puts("ok tinkerd 2");
    while (fgets(line, sizeof line, stdin)) {
        a1[0] = a2[0] = a3[0] = 0;
        if (sscanf(line, "%31s %127s %127s %127s", cmd, a1, a2, a3) < 1) continue;
        if (!strcmp(cmd, "ps")) { cmd_ps(); continue; }
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
            target = atoi(a1);
            char path[64];
            snprintf(path, sizeof path, "/proc/%d/mem", target);
            if (memfd >= 0) close(memfd);
            memfd = open(path, O_RDWR);
            nres = 0;
            if (!alive()) printf("err processo %d não existe\n", target);
            else puts("ok");
            continue;
        }
        if (!target) { puts("err nenhum jogo escolhido"); continue; }
        if (!alive()) { puts("err o jogo fechou"); continue; }
        if (!strcmp(cmd, "regions")) cmd_regions();
        else if (!strcmp(cmd, "ranges")) puts(set_ranges(a1) ? "ok" : "err região inválida");
        else if (!strcmp(cmd, "search")) cmd_search(a1, a2);
        else if (!strcmp(cmd, "refine")) cmd_refine(a1);
        else if (!strcmp(cmd, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(cmd, "read")) cmd_read(a1, a2);
        else if (!strcmp(cmd, "write")) cmd_write(a1, a2, a3);
        else if (!strcmp(cmd, "writeall")) cmd_writeall(a1);
        else if (!strcmp(cmd, "pause")) puts(kill(target, SIGSTOP) ? "err não pausou" : "ok");
        else if (!strcmp(cmd, "resume")) puts(kill(target, SIGCONT) ? "err não continuou" : "ok");
        else if (!strcmp(cmd, "kill")) puts(kill(target, SIGKILL) ? "err não fechou" : "ok");
        else if (!strcmp(cmd, "reset")) { nres = 0; puts("ok 0"); }
        else printf("err comando desconhecido: %s\n", cmd);
    }
    return 0;
}
