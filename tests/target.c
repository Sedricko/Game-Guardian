// Processo alvo dos testes: guarda valores conhecidos e espera.
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

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
    printf("%d %lx %lx %lx %lx\n", getpid(), (unsigned long)&i[7], (unsigned long)&f[3], (unsigned long)v, (unsigned long)x);
    fflush(stdout);
    pause();
    return 0;
}
