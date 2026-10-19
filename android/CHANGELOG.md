# Android changelog

## 1.0.0 — 2026-10-19

First stable Android release, versionCode 10000, using a permanent RSA-4096
release signing identity. Includes an installable release APK, signed AAB,
public signing certificate and SHA-256 checksums. Release builds are not
Android-debuggable; the signing key is stored locally outside this repository.

Carries the verified fixes from 0.4.0 preview: private VPN consent, import-only
external links, shared split-routing policy, transient connection state,
default kill switch, safe encrypted storage, TOML/native validation, service
lifecycle handling, IPv6 protection, upstream network collection and pinned
TrustTunnel 1.1.7. Russian and English UI are included.

Preview installations signed with a debug certificate require uninstalling
before installing this release. Export or retain the original profile links/TOML
first: uninstalling deletes local profiles. Future releases must retain this
permanent signing identity.

Known boundaries remain documented in QA.md: Android controls crash restart;
system Always-on plus lockdown protects when the VPN process is absent.
Mixed application/destination through-VPN selections use full VPN with a warning.
The manual endpoint acceptance scenario covers TLS/HTTP2 TCP, not UDP/HTTP3 or
physical-device network coverage.
