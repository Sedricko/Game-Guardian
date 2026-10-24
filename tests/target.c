// Processo alvo dos testes: guarda valores conhecidos e espera.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// Imitação das estruturas do IL2CPP (Unity) que o daemon lê para dar nome aos campos
struct ty { void *data; uint32_t bits; };  // bits: attrs (16) | tipo (8) << 16
struct fi { const char *name; struct ty *type; void *parent; int32_t offset; uint32_t token; };
struct klass { void *image, *gc; const char *name, *ns; void *pad[4]; struct fi *fields; };
static struct ty t_r4 = {0, 0x0cu << 16}, t_i4 = {0, 0x08u << 16}, t_static = {0, (0x08u << 16) | 0x10};
static struct klass player;
// nomes separados por \0, como no metadata do IL2CPP (o daemon procura \0Nome\0)
static const char names[] = "\0Player\0Game";
static struct fi player_fields[3];

// em .data (dentro do arquivo do programa): base fixa, como a de uma .so de jogo
static void *volatile root = (void *)1;

int main(void) {
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
    player = (struct klass){0, 0, names + 1, names + 8, {0}, player_fields};
    player_fields[0] = (struct fi){"hp", &t_r4, &player, 0x10, 0};
    player_fields[1] = (struct fi){"gold", &t_i4, &player, 0x14, 0};
    player_fields[2] = (struct fi){"instances", &t_static, &player, 0, 0};
    void **obj = malloc(64);
    obj[0] = &player;
    *(float *)((char *)obj + 0x10) = 99.5f;
    *(int *)((char *)obj + 0x14) = 4321;
    printf("%d %lx %lx %lx %lx %lx\n", getpid(), (unsigned long)&i[7], (unsigned long)&f[3], (unsigned long)v, (unsigned long)x,
           (unsigned long)obj);
    fflush(stdout);
    pause();
    return 0;
}
