# Jsoup y OkHttp funcionan sin reglas extra; el plugin de serialización genera las suyas.
# Conservar nombres de las clases serializables de la caché (los archivos JSON se leen por nombre de campo).
-keepclassmembers class com.gabriel.letrasenvivo.letras.modelos.** { *; }
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
