# Matrix SMS R8 rules: shrink libraries, but keep all app code and crypto intact (no obfuscation so crash logs stay readable).
-dontobfuscate
-keep class com.example.emit.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
