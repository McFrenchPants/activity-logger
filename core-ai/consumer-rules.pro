# ProGuard/R8 consumer keep rules for core-ai (ADR-023).
#
# The ML Kit GenAI schema compiler generates a response schema from @Generable
# classes and binds decoded model output back onto their members. R8 must therefore
# not rename, remove or strip those classes or their members, or structured
# decoding fails at runtime in a release build only -- the worst kind of failure to
# find late. These are shipped as *consumer* rules so every app module that depends
# on core-ai inherits them without having to know ML Kit is in here at all.
#
# Package names below were read from genai-schema 1.0.0-alpha1 itself
# (com.google.mlkit.genai.schema.annotations.Generable / .Guide, and the generated
# com.google.mlkit.genai.schema.guided.GenerableProvider implementations). The
# library is alpha with no deprecation policy: re-check these names whenever the
# pinned version moves.

-keepattributes *Annotation*,Signature,InnerClasses,EnumEntries,RuntimeVisibleAnnotations

# Keep the schema annotations themselves, and keep them readable at runtime.
-keep @interface com.google.mlkit.genai.schema.annotations.Generable
-keep @interface com.google.mlkit.genai.schema.annotations.Guide

# Keep every @Generable-annotated class and all of its members (constructors,
# fields and the Kotlin data-class accessors the decoder binds to).
-keep @com.google.mlkit.genai.schema.annotations.Generable class * { *; }
-keepclassmembers @com.google.mlkit.genai.schema.annotations.Generable class * { *; }

# Keep members carrying field-level @Guide constraints even in classes that are not
# themselves annotated (e.g. nested holders).
-keepclassmembers class * {
    @com.google.mlkit.genai.schema.annotations.Guide <fields>;
    @com.google.mlkit.genai.schema.annotations.Guide <methods>;
}

# Keep the schema-compiler-generated provider classes and the runtime interfaces
# they implement; they are discovered reflectively, never called directly. The
# generated name is "<AnnotatedClass>_GeneratedProvider", in the annotated class's
# own package (observed in generated output for genai-schema-compiler 1.0.0-alpha1).
-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider { *; }
-keep class **_GeneratedProvider { *; }
-keep class com.google.mlkit.genai.schema.guided.** { *; }
