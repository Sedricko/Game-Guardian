# SDK do Claude: os modelos são lidos e escritos pelo Jackson por reflexão; sem isso o R8 apaga e a resposta não abre
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**
# gerador de schema JSON do SDK (saídas estruturadas, que o Tinker não usa) cita reflexão que o Android não tem
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
