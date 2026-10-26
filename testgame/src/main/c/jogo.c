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

// roda sozinha 10 vezes por segundo (o "tick" do jogo): "quais rodam" acha ela e não acha o bônus sem tocar no ouro
static volatile int ticks;
__attribute__((noinline, visibility("default"))) void jogo_tick(void) { ticks++; }

JNIEXPORT void JNICALL Java_dev_tinker_testgame_GameActivity_tick(JNIEnv *env, jobject self) { (void)env; (void)self; jogo_tick(); }

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

// Unity IL2CPP de mentira: a classe Jogo.Heroi com nome (string), posição (Vector3), mochila (array de 7) e ouro.
// A ficha acha a classe pelo objeto do ouro (987654) e mostra "Herói", (1.5, 2, -3) e 7 itens, como num jogo Unity.
struct il2ty { void *data; uint32_t bits; };  // bits: attrs | tipo << 16
struct il2fi { const char *name; struct il2ty *type; void *parent; int32_t offset; uint32_t token; };
struct il2klass { void *image, *gc; const char *name, *ns; void *pad[4]; struct il2fi *fields; };
static const char heroi_nomes[] = "\0Heroi\0Jogo";
static struct il2ty t_i4 = {0, 0x08u << 16}, t_str = {0, 0x0eu << 16}, t_vec = {0, 0x11u << 16}, t_arr = {0, 0x1du << 16};
static struct il2klass heroi_k;
static struct il2fi heroi_f[4];
__attribute__((used)) static uint64_t heroi[8], heroi_nome[4], heroi_mochila[4];

__attribute__((constructor)) static void fake_unity(void) {
    heroi_k = (struct il2klass){heroi_f, 0, heroi_nomes + 1, heroi_nomes + 7, {0, (void *)(uintptr_t)(0x12u << 16)}, heroi_f};
    heroi_f[0] = (struct il2fi){"ouro", &t_i4, &heroi_k, 0x10, 0};
    heroi_f[1] = (struct il2fi){"nome", &t_str, &heroi_k, 0x18, 0};
    heroi_f[2] = (struct il2fi){"pos", &t_vec, &heroi_k, 0x20, 0};
    heroi_f[3] = (struct il2fi){"mochila", &t_arr, &heroi_k, 0x30, 0};
    static const uint16_t nome[] = {'H', 'e', 'r', 0xf3, 'i'};  // Il2CppString: klass, monitor, tamanho, UTF-16
    uint8_t *s = (uint8_t *)heroi_nome;
    *(int32_t *)(s + 2 * sizeof(void *)) = 5;
    memcpy(s + 2 * sizeof(void *) + 4, nome, sizeof nome);
    heroi_mochila[3] = 7;  // Il2CppArray: klass, monitor, bounds, max_length
    uint8_t *o = (uint8_t *)heroi;
    *(void **)o = &heroi_k;
    *(int32_t *)(o + 0x10) = 987654;
    *(void **)(o + 0x18) = heroi_nome;
    float pos[3] = {1.5f, 2, -3};
    memcpy(o + 0x20, pos, sizeof pos);
    *(void **)(o + 0x30) = heroi_mochila;
}
