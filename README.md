# ScreenshotFaker

[简体中文](README_ZH.md) | English

---

## 📖 Introduction

**ScreenshotFaker** is a privacy‑protection tool for screenshots, equipped with strong anti‑detection capabilities.

It allows you to customize how your personal information is protected during screenshots, screen recordings, and screen sharing — preventing untrusted apps from maliciously capturing your sensitive content.

---

## ✨ Features

- **Protect private content from being captured in screenshots**  
  Prevents screenshot services from capturing sensitive on-screen content and allows users to replace it with a custom image.  
  Prevents screen recording services from capturing sensitive content; supports custom video stream replacement (local or network).  
  Supports configuring specific Freeform floating windows to be excluded from screenshot and screen recording capture.  
  Supports disabling screenshots for sensitive apps that allow them.

- **Comprehensively block malicious detection by specific apps**  
  Bypass malicious app detection of user screenshots and screen recordings.  
  Supports independently blocking apps' floating window detection, focus detection, and window integrity detection.  
  Supports more aggressive detection and filtering against “unruly” malware.

- **Enables stealthy screen capture, recording, and sharing**  
  The screenshot and screen recording feature bypasses malicious application-layer detection through direct low-level system calls.  
  Screen sharing supports not only local network sharing but also remote sharing over SSH.
  It also supports receiving screen sharing and remote control efficiently.

- **Custom trigger methods**  
  Most configurations are user‑customizable;  
  Supports system-log-based triggering for screen capture, recording, and sharing — not limited to conventional gestures.  
  Efficiently configure and manage each app using configuration templates.

- **Extreme stealth support**  
  Supports viewing screenshot and screen recording files in a floating window without being captured by screenshots.  
  Supports hiding this app from recent tasks.  
  Supports hiding the desktop icon and reopening the app through a reliable method.  
  Supports reinstalling with a custom package name and app attributes.  
  Retains screen capture, recording, and sharing capabilities even after the software is uninstalled.

- **Extreme privacy protection**  
  All configuration data is stored with strong encryption;  
  Page protection against screenshot‑based configuration leakage;  
  Supports automatic high‑strength encryption for screenshot and screen recording files;  
  Filenames support full randomization (Random characters and length);  
  All port communications are secured with high‑strength encryption;

- **Ultimate duress protection**  
  Supports an in-app password and a duress password. Entering the duress password triggers hardware key destruction, rendering the data immediately and permanently unusable.  
  Force-enable the timeout self‑destruct setting: if normal usage is not detected within the user‑defined time, it will be treated as duress and trigger automatic data self‑destruction.  
  Built-in tamper protection: any unauthorized data injection or modification will be treated as an anomaly and trigger automatic data self‑destruction.

- **More features coming soon...**

---

## ⚠️ Project Status

This project is currently in an early development stage. Bugs, incomplete features, and breaking changes may occur.

---

## ⚙️ Privilege Dependencies

This project's core functionality relies on **LSPosed** and **Shizuku**:

**LSPosed**
- Protect private content captured through screenshots and screen recordings.
- Supports taking screenshots on pages where screenshots are not allowed.
- Disable screenshots on pages that allow them.
- Block screenshot and screen recording detection by specific apps.
- Supports preventing specific apps from detecting floating windows.
- Block focus detection, floating window detection, and window integrity detection by specific apps.
- Supports setting floating windows for specific apps to enable screen capture passthrough.

**Shizuku**
- Enables stealthy screen capture, recording, and sharing.
- Triggers screen capture, recording, and sharing by matching system logs.
- Retains screen capture, recording, and sharing capabilities even after uninstallation.

**Root**
- Provides the same functionality as Shizuku, **but with stronger concealment**.
- The floating window cannot be detected or blocked by underlying apps.

**No privileges needed**
- Receive screen sharing from this app.
- In-app password and a duress password support.
- Hardware-level and software-level strong file encryption and decryption.
- View screenshot and screen recording files in a floating window that cannot be captured by screenshots.
- Hide this app from recent tasks.
- Hide the desktop icon and reopen the app through a reliable method.
- Reinstall with a custom package name and app attributes.

---

## 🚫 Non‑Commercial Statement

This project is initiated by the developer out of personal interest and for technical research purposes, and is **non-commercial** in nature:

- **Permanently Free**:  
  This project is completely free, with **no paid features, memberships, subscriptions, or in-app purchases**. All features are fully accessible to all users.

- **No Sponsorship Channels**:  
  The author has **never opened any sponsorship channels**, nor does the author **accept any financial donations** — to maintain the project's neutrality and purity.

- **Non-Profit Purpose**:  
  This project involves no commercial operations, and the author derives no direct or indirect financial benefit from it.

- **Research-Oriented**:  
  This project is consistently positioned for **security research, privacy protection, and software testing** — providing a research tool for the community, not a commercial product. Any commercial use of this project is the user's own initiative and is unrelated to this project.

- **Resale Prohibited**:  
  Resale, redistribution for profit, or commercial use of this project is strictly prohibited. Please obtain it only from this repository (GitHub) or other officially designated channels. The developer assumes no responsibility for any issues arising from unofficial sources.

---

## ⚖️ Disclaimer

- **Purpose Limitation**:  
  This project is intended for **privacy protection, security research, software testing, and educational purposes** only.  
  Do not use this project for any illegal purposes (including but not limited to exam cheating, data falsification, and financial fraud).

- **Consequences Warning**:  
  Bypassing screenshot detection with this software **may violate the terms of service of third-party applications**, and may result in account suspension, device restrictions, or other losses.  
  You should assess the risks before using it. The developer and contributors **are not responsible for any account bans, device restrictions, asset losses, or other consequences** arising from such use.

- **No Warranty**:  
  This software is provided under the terms of its license, **without any express or implied warranties**, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement.

- **Compatibility Disclaimer**:  
  This software **does not guarantee full compatibility with all OS versions, device models, or third-party applications**. The developer assumes no responsibility for functional issues or losses caused by system differences, application updates, or other uncontrollable factors.

- **Limitation of Liability**:  
  To the fullest extent permitted by applicable law, **in no event shall the author or contributors be liable** for any direct, indirect, incidental, special, or consequential damages arising out of or in connection with the use or inability to use this software, even if advised of the possibility of such damages.

- **User Responsibility**:  
  Users assume all legal responsibilities arising from the use of this project.

- **Final Interpretation**:  
  The final interpretation of this disclaimer belongs to the author of this project.

---

## 🙏 Acknowledgements

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [JSch](https://github.com/mwiede/jsch)
- [Argon2](https://github.com/P-H-C/phc-winner-argon2)
- [DisableFlagSecure](https://github.com/lsposed/DisableFlagSecure)
- [Transparent_screenshot](https://github.com/Dszsu/Transparent_screenshot)
- [scrcpy](https://github.com/Genymobile/scrcpy)
- [libssh2](https://github.com/libssh2/libssh2)
- [openssl](https://github.com/openssl/openssl)
- [apksig](https://github.com/google/apksig)

---

## 💬 Contact

- QQ: https://qm.qq.com/q/j2NM49cd8c
- Telegram: https://t.me/ScreenshotFaker

You are welcome to submit issues, suggestions, or bug reports via GitHub Issues.

---

## ⭐ Support the Project

If you find this project helpful, or if you recognize its value in technical research, consider giving it a ⭐ on GitHub.

Your support helps more people discover this project, and also lets the author feel the significance of continued maintenance.

Thank you for your recognition.
