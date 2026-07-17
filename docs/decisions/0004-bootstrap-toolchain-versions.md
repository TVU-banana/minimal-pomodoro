# ADR 0004：采用可解析的稳定 Android 构建工具链

## 决策

项目锁定 Android Gradle Plugin 9.1.1 与 Gradle 9.3.1。AndroidX Core、Lifecycle 与 Coroutines 使用初始化时仓库可解析的更新稳定版本。

## 原因

Lint 的版本元数据建议 AGP 9.3.0 与 Gradle 9.6.1，但 2026-07-17 从 `google()`、Maven Central 和 Gradle Plugin Portal 实际解析 `com.android.application:9.3.0` 失败。9.1.1 已使用 JDK 17、SDK Platform 37.0 完成单元测试、Lint 和 R8 Release 构建。

## 替代方案与兼容性

不采用无法解析、因而无法复现的 9.3.0。待官方仓库能够稳定解析后，通过显式依赖升级 PR 同时更新 Wrapper、版本目录和依赖锁，并重新运行完整门禁。
