# Правила для приложений, которые подключают :speech.
#
# sherpa-onnx вызывает Kotlin-классы из JNI по именам, поэтому R8/ProGuard
# не должен их переименовывать или удалять. Без этого release-сборка падает
# с UnsatisfiedLinkError или NoSuchMethodError уже на устройстве.
#
# Правило широкое намеренно: R8 не видит, какие поля конфигов читает native,
# а AAR sherpa-onnx не поставляет собственных keep-правил (proguard.txt пуст).
# Сузить до конкретных классов можно только вместе с проверкой release-сборки
# на устройстве.

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# diarizationCallback отдельным правилом не покрывается: native получает
# kotlin.jvm.functions.Function3 и вызывает invoke(), а сам метод помечен
# @Keep (AGP сохраняет аннотированные члены). Сигнатура в старом правиле
# (void) не совпадала с реальной (int), поэтому правило было мёртвым.
