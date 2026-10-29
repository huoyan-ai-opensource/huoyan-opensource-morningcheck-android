# 实验签名

[English](README.en.md)

`xinhai-debug-demo.jks` 是随火眼开源零售项目公开的实验签名，本项目复用同一份密钥。它仅用于非商业开源实验和测试 Release 打包，不能用于商业发布，也不能代表可信的生产发布身份。

| 配置 | 公开值 |
| --- | --- |
| 密钥库文件 | `signing/xinhai-debug-demo.jks` |
| 格式 | JKS |
| 别名 | `xinhai-debug-demo` |
| 密钥库密码 | `xinhai-debug-demo` |
| 密钥密码 | `xinhai-debug-demo` |

默认 Release 使用此签名，Debug 继续使用本机 Android 调试签名。两者通常不能直接覆盖安装；切换签名前先备份员工、设置和晨检记录，不能为了安装测试包直接清除业务设备数据。使用相同签名升级时递增 `versionCode`。

正式发布使用自己的密钥，在未入库的 `local.properties` 中完整填写 `signing.storeFile`、`signing.storePassword`、`signing.keyAlias` 和 `signing.keyPassword`。相对密钥路径以仓库根目录为基准；配置不完整时构建会失败。示例见 [local.properties.example](../local.properties.example)。正式私钥和密码不得入库。

签名限制仅针对这份公开实验密钥，App 自有源码的使用规则见 [USAGE.md](../USAGE.md)。
