# Linux Chromium sandbox capability

`chromium-seccomp.json` is the upstream Microsoft Playwright v1.56.1 profile:
https://raw.githubusercontent.com/microsoft/playwright/v1.56.1/utils/docker/seccomp_profile.json

Upstream usage: https://playwright.dev/docs/docker . It retains default ERRNO and
adds clone/setns/unshare for user namespaces; it is not seccomp=unconfined.
No privileged mode, added capabilities, host IPC or sandbox-disabling Chrome flags
are authorized. Only a new owned, nonroot, network-none capability probe uses it.
The canonical local file SHA256 is
`d4906af343fd3bdf044a774ff6969bc506fcd141dab5acc8d02d8323e9991c44`.
The profile follows the upstream Apache-2.0 license; see
https://github.com/microsoft/playwright/blob/v1.56.1/LICENSE .
Git pins this file to LF so Windows checkout conversion cannot invalidate its SHA.

A capability PASS cannot stand in for the six real DOM/API requests. If namespaces
remain denied by another kernel/security boundary, record NOT_RUN and do not add
capabilities or disable sandboxing. Existing services and profiles stay untouched.
