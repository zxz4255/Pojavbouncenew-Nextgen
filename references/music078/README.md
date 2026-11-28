# 078 GitHub protocol references / attribution

Retrieved for the online KuGou concept-login task. Snapshots in this directory are reference material, not runtime dependencies. Upstream Apache-2.0 license texts are retained. Kotlin implementation changes and regression fixtures were made for this project; the project remains under its existing GPL-3.0-or-later license, subject to retained third-party notices.

## QueMusic
- Repository: https://github.com/BroNekoX/QueMusic
- PR: https://github.com/BroNekoX/QueMusic/pull/89
- Pinned commit: `b1c8fd901fdc61a4b02359483364cbd7415a038e`
- Snapshot `QueMusic-KugouApi.cpp`: upstream `api/KugouApi.cpp`; `QueMusic-LICENSE`: root LICENSE.
- Informed three-stage concept authorization, client identity, signature and tracker-key diagnosis. Missing/invalid web token must not be called a lack of VIP.

## lxserver
- Repository: https://github.com/zues0077/lxserver
- Release: https://github.com/zues0077/lxserver/releases/tag/v1.5.41
- Pinned commit: `6ba3d7d16dd55e9bdc8b37f6c3365b817408e840`
- `lxserver-kglite.ts`: `src/server/musicAccount/resolvers/kglite.ts`.
- `lxserver-kugou.ts`: `src/server/musicAccount/qrLogin/kugou.ts`.
- `lxserver-kugouLite.test.ts`: `test/kugouLite.test.ts`.
- `lxserver-LICENSE`: root LICENSE.
- Informed exact-body lite signature, verify/authorization router vs un-routed `/tracker/v5/url`, resource POST for quality hashes/audioId, QR create/poll app/version and device consistency.
- Only account-authorized playback/login protocol was adapted into `KugouPlayback.kt` / `KugouClient.kt`. Random forwarded-IP, ad/reward collection and unrelated upstream services were NOT integrated. The saved full resolver contains unrelated code for reproducible source review; it is not compiled or executed by this project.

## Encrypted-container investigation
GitHub search also surfaced Aaron-gx/KGG-Decryptor and other KuGou unlock projects. They were used only to distinguish encrypted containers from ordinary audio and understand that a matching local key may be required. Their decryption code was not copied, validated or integrated. This patch is NOT a KGG decryptor. Header detection rejects recognized KGM/KGG-family and VPR signatures, not a claim of covering every version.

## Verification boundary
Pinned upstream behavior is evidence of an implementation approach, not a guarantee of current platform service or VIP entitlement. No live user credential was provided or used; local tests use fabricated tokens/hashes and mocked responses. See `078-MUSIC-FIXES.md`.
