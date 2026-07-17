# ADR 0006：关闭未使用的 ViewBinding 并增加可安装 Preview 构建

## 决策

项目继续使用原生 XML Views，但关闭未被源码使用的 ViewBinding 代码生成，并移除未直接使用的 Lifecycle ViewModel KTX 依赖。增加 `preview` 构建类型：它继承 Release 的 R8 代码压缩和资源裁剪，同时仅使用本地 Debug 证书签名，供个人真机验收。

## 原因

当前界面统一通过 `findViewById` 和自定义 Canvas 访问 View，生成 Binding 类只会增加构建工作和少量发布代码。普通 Release APK 保持未签名，避免误把开发证书当成正式发布身份；Preview APK 则兼顾接近 Release 的体积和直接安装能力。

## 兼容性影响

现有界面和数据格式不变。若以后采用 ViewBinding，应通过独立变更重新启用并实际迁移界面代码。Preview 只能用于测试，公开发布必须使用项目所有者保管的正式签名密钥。
