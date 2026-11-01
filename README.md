# 群晖助手 Flutter 测试构建

此构建从 Gitee 上游固定提交 `cf03faa7e1e0000b1aa647ed975c4de64679bed4` 下载源码并叠加本次修改。压缩包内不包含本地签名、配置或缓存。

每次运行生成临时签名密钥，构建 release 测试 APK；应用 ID 为 `fit.flutter.dsm_helper.local`，与原版并存。每次构建签名不同，后续测试包无法直接覆盖本次安装。正式长期使用需配置安全保存的固定签名。
