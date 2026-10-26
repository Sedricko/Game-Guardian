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
