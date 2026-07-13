# Keep release crash reports retraceable while avoiding real source file names
# in stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Rust lyrics core uses JNI symbol names derived from these native method
# owners. Preserve native method names without disabling general shrinking.
-keepclasseswithmembernames class * {
    native <methods>;
}
