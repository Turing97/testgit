# NAS 助手（DSM 7）

安卓实验版：HTTPS 登录、一次性验证码、File Station 共享文件夹与目录浏览，以及尝试读取 DSM 状态与存储信息。状态接口依 DSM 版本和权限可能不可用；不虚构数据。只读功能，不执行删除、上传或文件修改。

输入完整 HTTPS 地址，例如 `https://nas.example.com:5001`。手机需要能访问 NAS，证书须被安卓系统信任。密码仅用于登录请求，不写入本地；会话仅保存在应用进程中。当前不支持 QuickConnect ID、推送二次验证、下载及自签证书。

在 GitHub Actions 执行 Build Android APK 工作流，下载 NAS-assistant-debug 构建产物。首次安装前，需在安卓允许对应应用安装未知来源应用。该版本为调试包，尚需在实际 DSM 7 设备验证。
