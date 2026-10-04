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
  Data transmission for problem feedback employs dynamic end-to-end encryption.

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

## 🚫 Non-Commercial Statement

This project was started by the developer out of personal interest and is
**non-commercial** in nature:

- **Permanently free**: no paid features, memberships, subscriptions, or in-app purchases
- **No sponsorship channels**: the author has never opened sponsorship channels and accepts no donations of any kind
- **Research & privacy-oriented**: positioned as a personal-privacy research tool, not a commercial product

**License is GPL-3.0 only — no commercial exceptions.** This project is
offered under the terms of the GPL-3.0 (see [LICENSE](LICENSE)), and **every
use must comply with that license in full**. What GPL requires — source
availability and the same license for derivatives — is exactly what it means
to use this project. **Commercial use that cannot accept GPL terms does not
have the author's authorization**: the author does not offer, and will not
negotiate, dual licensing, commercial exceptions, or proprietary
redistribution. Reselling builds for profit while ignoring GPL obligations
is copyright infringement.

- **Attribution and statement integrity**:  
  Redistribution of unmodified builds is permitted only together with this
  statement and proper attribution. **Removing, altering, or obscuring this
  non-commercial statement when redistributing is prohibited.**
- **Official channels only**:  
  Obtain the app **only** from this repository (GitHub) or its official
  Releases. Builds from any other source are unofficial, unverified, and used
  entirely at the downloader's own risk.

## ⚠️ Disclaimer

> **This tool is dual-use. It exists to protect YOUR privacy on YOUR device.
> Using it against devices you do not own, or to deceive people about content
> you promised to show, is misuse the author explicitly condemns.**

- **Purpose limitation**:  
  This project is intended for **protecting the user's own privacy, security
  research, and educational purposes** — controlling what untrusted apps may
  capture from YOUR OWN screen. Do not use it for any illegal purpose,
  **including but not limited to exam cheating, evidence falsification,
  financial fraud, or capturing content on devices you do not own or without
  the owner's consent**.
- **Anti-stalkerware stance**:  
  This tool's stealth features (hidden icon, custom package name, persistence
  after uninstall) exist to protect the USER from being coerced into
  surrendering their privacy — NOT to enable covert monitoring of others.
  **Deploying this tool on another person's device without their knowledge
  is categorically misuse**, may be illegal (stalking, wiretapping, or
  computer-misuse laws), and the author provides no support for such use.
- **Third-party ToS and jurisdiction**:  
  Bypassing screenshot/recording detection **may violate the terms of service
  of third-party applications and the laws of your jurisdiction**. It is the
  user's sole responsibility to determine legality before use. The developer
  is not responsible for account bans, device restrictions, asset freezes,
  or any other consequences.
- **Duress features are best-effort**:  
  The duress password and timeout self-destruct are defensive conveniences,
  **not guaranteed anti-forensics**. A sufficiently capable adversary with
  physical access may circumvent them. Do not rely on them as your only
  protection for life-critical secrets.
- **No warranty**:  
  This software is provided under GPL-3.0, **without any express or implied
  warranties**, including merchantability, fitness for a particular purpose,
  and non-infringement. Nothing here is a promise of undetectability or of
  data recoverability after self-destruct.
- **Limitation of liability**:  
  To the fullest extent permitted by applicable law, **the author and
  contributors are not liable** for any damages arising from the use or
  inability to use this software, including but not limited to data loss
  (including self-destruct-triggered loss), legal consequences, or security
  incidents.
- **User responsibility**:  
  Users assume all legal responsibilities arising from their use of this
  project, and from obtaining it from any channel.
- **Final interpretation**:  
  The final interpretation of this disclaimer belongs to the author.

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
