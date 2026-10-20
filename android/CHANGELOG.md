# Android preview changes

## Unreleased preview

The preview includes import-only external links, private VPN consent, shared
split-routing policy, process-local connection state, default kill switch,
non-destructive encrypted storage, TOML/adapter/native validation, service
lifecycle handling, IPv6 protection and upstream network collection. The official
TrustTunnel 1.1.7 adapter and build tooling are pinned by checksums.

Review follow-up rejects `/0` destination rules and whole-family exclusions,
restores global IPv6 link-import routes, and serializes ordinary TOML tables for
adapter compatibility with escaped credentials and PEM certificates.

Release signing remains parameterized by environment variables or CI secrets.
Official release publishing, versioning and signing are managed by the original
project. No fork signing identity or stable-release claim is part of this PR.
