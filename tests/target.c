// Processo alvo dos testes: guarda valores conhecidos e espera.
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

// Imitação das estruturas do IL2CPP (Unity) que o daemon lê para dar nome aos campos
struct ty { void *data; uint32_t bits; };  // bits: attrs (16) | tipo (8) << 16
struct fi { const char *name; struct ty *type; void *parent; int32_t offset; uint32_t token; };
struct mi;
struct klass { void *image, *gc; const char *name, *ns; void *pad[4]; struct fi *fields; struct mi **methods; };
// MethodInfo do Unity 2018+: código, invoker, nome, classe dona, tipo de retorno
struct mi { void *code, *invoker; const char *name; struct klass *klass; struct ty *ret; };
static struct ty t_r4 = {0, 0x0cu << 16}, t_i4 = {0, 0x08u << 16}, t_static = {0, (0x08u << 16) | 0x10};
static struct ty t_str = {0, 0x0eu << 16}, t_vec = {0, 0x11u << 16}, t_arr = {0, 0x1du << 16};
static struct klass player;
// nomes separados por \0, como no metadata do IL2CPP (o daemon procura \0Nome\0)
static const char names[] = "\0Player\0Game";
static struct fi player_fields[6];

// em .data (dentro do arquivo do programa): base fixa, como a de uma .so de jogo
static void *volatile root = (void *)1;

// "o que escreve aqui": uma thread muda o contador o tempo todo, como o jogo mudando a vida
static volatile int ticks;
// "método" chamado o tempo todo, para o contador de chamadas
__attribute__((noinline)) void tick_once(void) { ticks++; __asm__ volatile("" ::: "memory"); }
static void *ticker(void *arg) {
    (void)arg;
    for (;;) { tick_once(); usleep(20000); }
    return NULL;
}

// função com nome que chama outra: "calls" acha tick_once dentro dela (o volatile tira a chamada do fim, que viraria jmp)
__attribute__((noinline)) int tinker_calls_tick(void) { tick_once(); return ticks; }

// código que nunca roda: o teste escreve nele (patch) e confere
__attribute__((noinline)) int never_called(int x) { return x * 3 + 7; }

int main(void) {
    pthread_t th;
    pthread_create(&th, NULL, ticker, NULL);
    volatile int *i = malloc(4096);
    volatile float *f = malloc(4096);
    i[7] = 123456789;
    i[8] = 555;  // endereço múltiplo de 8 com zero depois: casa também como Qword
    i[9] = 0;
    f[3] = 3.5f;
    // cadeia de ponteiros: root → a; a+0x10 → b; b+0x20 = valor
    char *a = malloc(256), *b = malloc(256);
    *(char **)(a + 0x10) = b;
    root = a;
    volatile int *v = (int *)(b + 0x20);
    *v = 424242;
    // como o ObscuredInt do Unity (Anti-Cheat Toolkit): chave sorteada e valor ^ chave lado a lado
    volatile int *x = malloc(64);
    x[0] = 0x5A3C9D21;
    x[1] = 777777 ^ 0x5A3C9D21;
    // objeto IL2CPP: Game.Player { float hp em +0x10; int gold em +0x14 } e um campo estático que não aparece
    // image: qualquer ponteiro válido; pad[1] = bits do byval_arg com o tipo "classe" (0x12)
    player = (struct klass){player_fields, 0, names + 1, names + 8, {0, (void *)(uintptr_t)(0x12u << 16)}, player_fields};
    player_fields[0] = (struct fi){"hp", &t_r4, &player, 0x10, 0};
    player_fields[1] = (struct fi){"gold", &t_i4, &player, 0x14, 0};
    player_fields[2] = (struct fi){"nick", &t_str, &player, 0x18, 0};
    player_fields[3] = (struct fi){"pos", &t_vec, &player, 0x20, 0};
    player_fields[4] = (struct fi){"bag", &t_arr, &player, 0x30, 0};
    player_fields[5] = (struct fi){"instances", &t_static, &player, 0, 0};
    static struct mi m_gold, m_tick;
    static struct mi *player_methods[3];
    m_gold = (struct mi){(void *)never_called, 0, "get_gold", &player, &t_i4};
    m_tick = (struct mi){(void *)tick_once, 0, "Tick", &player, &t_i4};
    player_methods[0] = &m_gold;
    player_methods[1] = &m_tick;
    player.methods = player_methods;
    void **obj = malloc(64);
    obj[0] = &player;
    *(float *)((char *)obj + 0x10) = 99.5f;
    *(int *)((char *)obj + 0x14) = 4321;
    // nick: Il2CppString "Ação" (klass, monitor, tamanho, UTF-16); pos: Vector3 (1.5, 2, -3); bag: array com 7 itens
    static const uint16_t acao[] = {'A', 0xe7, 0xe3, 'o'};
    char *nick = calloc(1, 64);
    *(int *)(nick + 16) = 4;
    memcpy(nick + 20, acao, sizeof acao);
    *(char **)((char *)obj + 0x18) = nick;
    float *pos = (float *)((char *)obj + 0x20);
    pos[0] = 1.5f; pos[1] = 2; pos[2] = -3;
    char *bag = calloc(1, 64);
    *(long *)(bag + 24) = 7;
    *(char **)((char *)obj + 0x30) = bag;
    // como o "X8" do GameGuardian: valor ^ chave, com a chave 8 bytes depois
    volatile int *y = malloc(64);
    y[2] = 0x3B7E11C5;
    y[0] = 31337 ^ 0x3B7E11C5;
    // inteiros em dobro, como no Dart (Flutter) e no JavaScript: 40404 fica 80808
    volatile int *z = malloc(64);
    z[0] = 40404 * 2;
    volatile long long *q = malloc(64);
    q[0] = 9000000000LL * 2;
    // ObscuredLong do Unity: chave de 8 bytes e valor ^ chave logo depois
    volatile unsigned long long *w = malloc(64);
    w[0] = 0x1F2E3D4C5B6A7988ULL;
    w[1] = 7777777777ULL ^ 0x1F2E3D4C5B6A7988ULL;
    // correio do jogo: recompensa ainda não pega (0) e 12345 presentes
    volatile int *mail = malloc(64);
    mail[0] = 0;
    mail[1] = 12345;
    // tabela do Lua 5.4 (xLua): { gold = 31415 (inteiro), leaf = 2.5 (decimal), name = "hero", bag = {} }
    // TString: tt 4 em +8, tamanho em +11, texto em +24; nó: valor, tipo em +8, tipo da chave em +9, chave em +16
    static const char *keys[] = {"gold", "leaf", "name", "bag"};
    unsigned char *str[4], *heroname = calloc(1, 64), *lt = calloc(1, 64), *sub = calloc(1, 64);
    for (int k = 0; k < 4; k++) {
        str[k] = calloc(1, 64);
        str[k][8] = 0x04;
        str[k][11] = (unsigned char)strlen(keys[k]);
        memcpy(str[k] + 24, keys[k], strlen(keys[k]));
    }
    heroname[8] = 0x04; heroname[11] = 4; memcpy(heroname + 24, "hero", 4);
    unsigned char *nodes = calloc(8, 24);
    long long gold = 31415; double leaf = 2.5;
    memcpy(nodes + 0, &gold, 8); nodes[8] = 0x03;
    memcpy(nodes + 24, &leaf, 8); nodes[24 + 8] = 0x13;
    memcpy(nodes + 48, &heroname, 8); nodes[48 + 8] = 0x44;
    sub[8] = 0x05;  // tabela vazia
    memcpy(nodes + 72, &sub, 8); nodes[72 + 8] = 0x45;
    for (int k = 0; k < 4; k++) { nodes[k * 24 + 9] = 0x44; memcpy(nodes + k * 24 + 16, &str[k], 8); }
    lt[8] = 0x05; lt[11] = 3;  // 2^3 = 8 nós
    memcpy(lt + 24, &nodes, 8);
    // ponteiro para a memória do jogo: as duas metades não podem passar por um Dword 🔒 (chave + valor cifrado)
    void **pp = malloc(64);
    pp[0] = a;
    // memória nova, ainda não escrita: o teste escreve nela e "onde o jogo escreve" tem que contar na região A
    char *fresh = mmap(NULL, 1 << 20, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    printf("%d %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx %lx\n", getpid(), (unsigned long)&i[7], (unsigned long)&f[3], (unsigned long)v, (unsigned long)x,
           (unsigned long)obj, (unsigned long)y, (unsigned long)z, (unsigned long)q, (unsigned long)w, (unsigned long)&ticks, (unsigned long)never_called, (unsigned long)mail, (unsigned long)tick_once,
           (unsigned long)lt, (unsigned long)nodes, (unsigned long)pp, (unsigned long)fresh);
    fflush(stdout);
    pause();
    return 0;
}
