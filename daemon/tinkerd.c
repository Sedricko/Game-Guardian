// tinkerd: daemon de memória do Tinker. Roda como root e fala por stdin/stdout,
// um comando por linha; cada resposta termina com "ok ..." ou "err ...".
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

static pid_t target;
static int memfd = -1;
static int type = 2;
// shortcut: resultados num vetor em RAM (8 bytes cada), trocar por arquivo mmap se passar de dezenas de milhões
static uint64_t *res;
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

static int parse_value(const char *s, value_t *v) {
    char *end;
    if (TYPES[type].isfloat) {
        v->f = strtod(s, &end);
    } else {
        v->raw = (uint64_t)strtoll(s, &end, 0);
        int bits = TYPES[type].size * 8;
        if (bits < 64) v->raw &= (1ULL << bits) - 1;  // -1 e 4294967295 viram o mesmo dword
    }
    return end != s && (*end == 0 || *end == '\n');
}

static int matches(const unsigned char *p, const value_t *v) {
    if (TYPES[type].size == 4 && TYPES[type].isfloat) {
        float x; memcpy(&x, p, 4);
        return fabs(x - v->f) <= 1e-6 * fmax(1, fabs(v->f));  // "1.1" digitado vs 1.1f guardado
    }
    if (TYPES[type].isfloat) {
        double x; memcpy(&x, p, 8);
        return fabs(x - v->f) <= 1e-9 * fmax(1, fabs(v->f));
    }
    uint64_t x = 0; memcpy(&x, p, TYPES[type].size);
    return x == v->raw;
}

static void encode(const value_t *v, unsigned char *out) {
    if (TYPES[type].size == 4 && TYPES[type].isfloat) { float x = (float)v->f; memcpy(out, &x, 4); }
    else if (TYPES[type].isfloat) memcpy(out, &v->f, 8);
    else memcpy(out, &v->raw, TYPES[type].size);
}

static void format(const unsigned char *p, char *out) {
    int sz = TYPES[type].size;
    if (TYPES[type].isfloat && sz == 4) { float x; memcpy(&x, p, 4); sprintf(out, "%.9g", x); return; }
    if (TYPES[type].isfloat) { double x; memcpy(&x, p, 8); sprintf(out, "%.17g", x); return; }
    uint64_t x = 0; memcpy(&x, p, sz);
    if (sz < 8 && (x >> (sz * 8 - 1))) x |= ~0ULL << (sz * 8);  // estende o sinal
    sprintf(out, "%" PRId64, (int64_t)x);
}

static void push(uint64_t a) {
    if (nres == cap) {
        cap = cap ? cap * 2 : 4096;
        res = realloc(res, cap * sizeof *res);
        if (!res) { puts("err sem memória"); exit(1); }
    }
    res[nres++] = a;
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

static void cmd_search(const value_t *v) {
    char path[64], line[1024];
    snprintf(path, sizeof path, "/proc/%d/maps", target);
    FILE *f = fopen(path, "r");
    if (!f) { printf("err não abriu %s: %s\n", path, strerror(errno)); return; }
    const size_t CHUNK = 1 << 20;
    unsigned char *buf = malloc(CHUNK);
    int sz = TYPES[type].size;
    nres = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long long s, e;
        char perms[8];
        int off = 0;
        if (sscanf(line, "%llx-%llx %7s %*s %*s %*s %n", &s, &e, perms, &off) < 3) continue;
        const char *name = off ? line + off : "";
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        // memória de GPU/driver trava ou derruba o processo se lida
        if (strstr(name, "/dev/") || strstr(name, "dmabuf") || strstr(name, "kgsl")) continue;
        for (uint64_t a = s; a < e; a += CHUNK) {
            size_t n = e - a < CHUNK ? e - a : CHUNK;
            if (read_mem(a, buf, n)) continue;
            for (size_t i = 0; i + sz <= n; i += sz)
                if (matches(buf + i, v)) push(a + i);
        }
    }
    fclose(f);
    free(buf);
    printf("ok %zu\n", nres);
}

static void cmd_refine(const value_t *v) {
    // shortcut: uma leitura por resultado, lento só se o refino começar com milhões
    size_t k = 0;
    unsigned char b[8];
    for (size_t i = 0; i < nres; i++)
        if (!read_mem(res[i], b, TYPES[type].size) && matches(b, v)) res[k++] = res[i];
    nres = k;
    printf("ok %zu\n", nres);
}

static void cmd_list(size_t n) {
    unsigned char b[8];
    char out[64];
    for (size_t i = 0; i < nres && i < n; i++) {
        if (read_mem(res[i], b, TYPES[type].size)) strcpy(out, "?");
        else format(b, out);
        printf("%" PRIx64 " %s\n", res[i], out);
    }
    printf("ok %zu\n", nres);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    char line[512], cmd[32], a1[128], a2[128];
    puts("ok tinkerd 1");
    while (fgets(line, sizeof line, stdin)) {
        a1[0] = a2[0] = 0;
        if (sscanf(line, "%31s %127s %127s", cmd, a1, a2) < 1) continue;
        value_t v;
        if (!strcmp(cmd, "ps")) cmd_ps();
        else if (!strcmp(cmd, "attach")) {
            target = atoi(a1);
            char path[64];
            snprintf(path, sizeof path, "/proc/%d/mem", target);
            if (memfd >= 0) close(memfd);
            memfd = open(path, O_RDWR);
            nres = 0;
            if (kill(target, 0) && errno == ESRCH) printf("err processo %d não existe\n", target);
            else puts("ok");
        } else if (!target) puts("err nenhum processo escolhido");
        else if (!strcmp(cmd, "search")) {
            int t = -1;
            for (int i = 0; i < NTYPES; i++) if (!strcmp(a1, TYPES[i].name)) t = i;
            if (t < 0) { printf("err tipo inválido: %s\n", a1); continue; }
            type = t;
            if (!parse_value(a2, &v)) printf("err valor inválido: %s\n", a2);
            else cmd_search(&v);
        } else if (!strcmp(cmd, "refine")) {
            if (!parse_value(a1, &v)) printf("err valor inválido: %s\n", a1);
            else cmd_refine(&v);
        } else if (!strcmp(cmd, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(cmd, "write") || !strcmp(cmd, "writeall")) {
            int all = cmd[5] == 'a';
            unsigned char b[8];
            if (!parse_value(all ? a1 : a2, &v)) { printf("err valor inválido: %s\n", all ? a1 : a2); continue; }
            encode(&v, b);
            if (!all) {
                if (write_mem(strtoull(a1, NULL, 16), b, TYPES[type].size)) printf("err falhou ao escrever: %s\n", strerror(errno));
                else puts("ok");
                continue;
            }
            size_t fail = 0;
            for (size_t i = 0; i < nres; i++) fail += write_mem(res[i], b, TYPES[type].size) != 0;
            printf("ok %zu %zu\n", nres - fail, fail);
        } else if (!strcmp(cmd, "reset")) { nres = 0; puts("ok 0"); }
        else printf("err comando desconhecido: %s\n", cmd);
    }
    return 0;
}
