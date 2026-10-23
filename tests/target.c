// Processo alvo dos testes: guarda valores conhecidos e espera.
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(void) {
    volatile int *i = malloc(4096);
    volatile float *f = malloc(4096);
    i[7] = 123456789;
    f[3] = 3.5f;
    printf("%d %lx %lx\n", getpid(), (unsigned long)&i[7], (unsigned long)&f[3]);
    fflush(stdout);
    pause();
    return 0;
}
