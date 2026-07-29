# scrcpy (Modified Version)

本目录包含从 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) 修改而来的版本。

## 原始项目

- **项目名称**: scrcpy
- **原始作者**: Genymobile
- **原始许可证**: Apache License 2.0
- **原始项目地址**: https://github.com/Genymobile/scrcpy

## 修改说明

本目录仅包含并修改了原项目 [scrcpy](https://github.com/Genymobile/scrcpy) 中 Android 服务端的主要源代码，其余部分未包含在本目录中。  
主要改动为：  
使服务端与客户端的数据传输摆脱对 ADB 的依赖，支持通过自定义的 TCP 端口通信，进而方便 ScreenshotFaker 使用 SSH 转发。

## 许可证

本目录下的代码（基于原 scrcpy 修改）沿用原始项目的 **Apache License 2.0** 许可证。

详细的许可证条款请查看本目录下的 [LICENSE](LICENSE) 文件，或访问：
http://www.apache.org/licenses/LICENSE-2.0

---
**本修改版本与原始项目并非同一项目，原始项目由 Genymobile 维护。**