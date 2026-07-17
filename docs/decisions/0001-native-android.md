# ADR 0001：原生 Android 与 Kotlin

采用 Kotlin、XML Views 和 Canvas。相比跨平台运行时，此方案更容易限制包体、后台资源和动画分配；相比 Java，Kotlin 与 AndroidX、Room 和结构化并发配合更可靠。项目不使用 NDK、Compose、Flutter、React Native 或反射式依赖注入。

