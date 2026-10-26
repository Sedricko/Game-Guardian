# O assistente (Gemini e Groq) usa só HTTP e org.json do próprio Android.
# ML Kit (🎯 Achar sozinho): o R8 em modo completo apagava campos que ele acha por reflexão
# ("Attempt to read from field ... on a null object reference" no aparelho, 25/10)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.common.internal.** { *; }
-keep class com.google.firebase.components.** { *; }
# O vigia da tela registra onde ela travou: com os nomes e as linhas do Tinker, dá para achar o código culpado
-keepnames class dev.tinker.** { *; }
-keepattributes SourceFile,LineNumberTable
