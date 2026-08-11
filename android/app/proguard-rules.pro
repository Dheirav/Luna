# kotlinx-serialization keeps its generated serializers via @Serializable companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.dheirav.cycletracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.dheirav.cycletracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
