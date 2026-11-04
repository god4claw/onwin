# Правила для приложений, которые подключают :speech.
#
# sherpa-onnx вызывает Kotlin-классы из JNI по именам, поэтому R8/ProGuard
# не должен их переименовывать или удалять. Без этого release-сборка падает
# с UnsatisfiedLinkError или NoSuchMethodError уже на устройстве.

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Колбэк прогресса диаризации вызывается из native-кода.
-keepclassmembers class com.example.speech.impl.SherpaTranscriber {
    private void diarizationCallback(int, int, long);
}
