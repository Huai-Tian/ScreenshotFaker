# ScreenshotFaker

**A software that supports user-defined screenshot functionality and boasts strong anti-detection capabilities.**

**一个支持用户自定义截屏功能的软件，并且具有很强的反检测能力**

This is a module that is currently under active development, supporting user customization of the content captured by screen captures and bypassing the software's screenshot detection.

这是一个正在积极开发中的模块，支持用户自定义被屏幕抓拍获取到的内容以及绕过软件的截屏检测

**Key Features (Under Development)** 
* **Replace the content obtained from the screenshot:** Replaces screenshot content with a custom image (Requires Xposed framework).
* **Render the software's screenshot detection ineffective:** Neutralizes the app's screenshot detection (Requires Xposed framework).
* **Unobtrusive screen capture, recording, and sharing:** Bypasses application layer detection via direct system-level calls (Requires Shell/Root privileges).
* **Hide desktop icon:** Non-privileged users need to specify a secret code to open this app, while Xposed users can still launch it from the Xposed Manager.
* **Use custom package name and software characteristics:** Supports custom package names and selecting whether to hide Shizuku and Xposed features (No privileges required).
* **Receive screen sharing from this app:** Receives screen sharing from this app over the LAN (No privileges required).

**核心功能（开发中）**
* **替换截屏获取到的内容:** 需要Xposed框架支持，将屏幕截图的内容替换为用户指定的图片
* **使软件的截屏检测无效:** 需要Xposed框架支持，让用户的截图事件跳过应用的截图检测
* **无痕的截屏录屏以及屏幕共享:** 需要Shell/Root特权，通过系统底层的直接调用绕过应用层的检测
* **隐藏桌面图标:** 无特权用户需要指定暗码打开此应用，Xposed用户仍然可以从Xposed管理器中打开此应用
* **使用自定义包名和软件特征:** 无需特权，支持自定义包名，选择是否隐藏Shizuku特征和Xposed特征
* **接收来自此应用的屏幕共享:** 无需特权，接收局域网中来自此应用的屏幕共享

**Important Notice**

This software is currently in the early stages of development. Many features are still under construction, and existing ones may have bugs or limitations. I sincerely apologize for any errors or issues you may encounter.

**重要提示**

本软件尚在开发早期阶段，大量的功能还在实现中，已实现的功能可能存在各种问题和不足，如果你在使用过程中遇到任何错误，我深感抱歉。

**Contact**

Welcome to join our Telegram channel for communication and feedback: [Telegram Channel](https://t.me/ScreenshotFaker)

Alternatively, you can directly submit issues and suggestions on the project's Issue page. I will actively respond and do my best to resolve them.

**联系方式**

欢迎加入我们的 Telegram 频道进行交流和反馈：[Telegram频道](https://t.me/ScreenshotFaker)

或者，你也可以直接在本项目的 Issue 页面提交问题和建议，我会积极回复并尽力解决。

**Thank you for your attention and support!**

**感谢你的关注和支持！**
