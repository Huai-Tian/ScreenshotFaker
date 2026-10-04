# Security Policy

## Reporting a Vulnerability

**Report privately — do NOT open a public issue.**

- **Preferred**: GitHub Private Vulnerability Reporting
  (Security tab → "Report a vulnerability")
- **Also accepted**: encrypted email to huaitian.behinder@gmail.com,
  GPG key `125F 7101 1717 0318 A318  F9C4 7FA3 DE0E 6B42 E01A`

Include: app version, device model, Android version, ROM,
LSPosed/Shizuku/Root mode, and steps to reproduce.

## Scope

The protection logic (capture replacement, FLAG_SECURE handling),
the stealth-capture implementation, the duress/self-destruct path,
crypto usage (file encryption, key destruction), and anything that
could crash the device, brick data, or leak the user's own secrets.

Out of scope:

- **Detection-bypass requests** — "app X still detects my screenshot"
  is a functional issue (open a normal issue with logs), not a
  security vulnerability.
- **Bypasses of this tool's protections by their owners** — e.g., a
  duress-password weakness report is in scope ONLY if it can be
  triggered by someone OTHER than the device owner.

## What to expect

Non-commercial personal project, maintained best-effort:
**no guaranteed response time**. Confirmed issues will be fixed
and announced via GitHub Security Advisories.

## Safe harbor

Good-faith research reported privately will not face legal action.