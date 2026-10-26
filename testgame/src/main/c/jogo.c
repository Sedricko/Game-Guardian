// Lib nativa do Jogo de Teste: o bônus do ouro sai de funções com nome, como num jogo de verdade (AddGold, GetReward).
// O Tinker acha pelo nome (tabela de símbolos), mostra quem chama quem e fixa o retorno com um patch.
#include <jni.h>

static volatile int bonus = 1;  // volatile: o compilador não troca a chamada pelo número

__attribute__((noinline, visibility("default"))) int jogo_bonus_ouro(void) { return bonus; }

static volatile int last;
__attribute__((noinline, visibility("default"))) int jogo_ganha_ouro(int ouro) {
    int b = jogo_bonus_ouro();
    last = b;  // depois da chamada: ela não vira um salto no fim (o "quem ela chama" procura a chamada)
    return ouro + b;
}

JNIEXPORT jint JNICALL Java_dev_tinker_testgame_GameActivity_ganhaOuro(JNIEnv *env, jobject self, jint ouro) {
    (void)env; (void)self;
    return jogo_ganha_ouro(ouro);
}

// Unreal de mentira (4.23+): bloco de nomes começando com "None" e "ByteProperty", a tabela dos blocos e a lista de
// objetos (GUObjectArray) nos dados da lib, como no libUE4.so. O Tinker acha "Jogador" (classe BP_Jogador_C) pelo nome.
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

void *GNameBlocks[8];  // FNamePool::Blocks
struct { void **Objects; void *PreAllocated; int32_t Max, Num, MaxChunks, NumChunks; } GObjObjects;
struct UObj { void *vt; int32_t flags, index; struct UObj *cls; uint32_t name, number; void *outer; int32_t ouro; };

static uint32_t add_name(uint8_t *b, uint32_t *off, const char *s) {
    uint16_t len = (uint16_t)strlen(s), h = (uint16_t)(len << 6);
    uint32_t at = *off;
    memcpy(b + at, &h, 2);
    memcpy(b + at + 2, s, len);
    *off = (at + 2 + len + 1) & ~1u;  // entradas de 2 em 2 bytes
    return at / 2;
}

__attribute__((constructor)) static void fake_unreal(void) {
    uint8_t *b = calloc(1, 131072);
    uint32_t off = 0;
    add_name(b, &off, "None");
    add_name(b, &off, "ByteProperty");
    add_name(b, &off, "IntProperty");
    uint32_t nClass = add_name(b, &off, "Class"), nPkg = add_name(b, &off, "Package");
    uint32_t nBp = add_name(b, &off, "BP_Jogador_C"), nJog = add_name(b, &off, "Jogador"), nWs = add_name(b, &off, "WorldSettings");
    GNameBlocks[0] = b;
    struct UObj *o = calloc(5, sizeof *o);
    uint32_t names[5] = {nPkg, nClass, nBp, nJog, nWs};
    struct UObj *cls[5] = {&o[1], &o[1], &o[1], &o[2], &o[1]};
    for (int i = 0; i < 5; i++) { o[i].index = i; o[i].name = names[i]; o[i].cls = cls[i]; }
    o[3].ouro = 1234;
    struct { void *obj; int32_t flags, cluster, serial, pad; } *items = calloc(65536, 24);
    for (int i = 0; i < 5; i++) items[i].obj = &o[i];
    void **chunks = calloc(1, sizeof(void *));
    chunks[0] = items;
    GObjObjects.Objects = chunks;
    GObjObjects.Max = 65536; GObjObjects.Num = 5; GObjObjects.MaxChunks = 1; GObjObjects.NumChunks = 1;
}
