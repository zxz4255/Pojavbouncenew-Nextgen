# Focused 081 online key retrieval tests

Requires JDK 25, Kotlin (tested 2.4.20), Node.js, Python 3 and Gson/dependency jars from prior suites. No user account or external service needed.

```sh
JAVA_HOME=/path/to/jdk25 \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
VERIFY_LIBS=/path/to/jars \
bash scripts/verify081/run.sh > scripts/verify081/results.txt 2>&1
```

The runner regenerates the independent Node request vectors, verifies upstream source hashes and production wiring, then compiles actual music core sources into a temporary directory. It reuses historical 078 host fixtures and 079 regressions **without editing them**. It explicitly includes the new KugouOnlineKeys source; the old historical runner describes the old file set and is not the runner for 081.

`OnlineKeyRegression.kt`: **144 assertions** in the final run. Standard/lite request JSON bytes and query signatures match the actual pinned upstream song_url_new module executed in Node (synthetic identity/clock, stubbed utility import). It verifies exact resource matching rather than en_hash aliases; explicit full-play authorization; rejection of missing/denied/preview/oversized/malformed/conflicting responses; safe error messages; cancellation and stale-account responses; local-first lookup; no online-key persistence; repeat job re-request; v3 no lookup; byte-exact synthetic v5 decrypt/import; original source preservation; no output on wrong/rejected keys; client logged-out/incomplete credentials and account epoch invalidation.

A process-local HTTPS URLStreamHandler intercepts only gateway.kugou.com/v6/priv_url. Tests then exercise the actual production HttpTransport, KugouClient onlineKey and MusicManager.download, with a loopback HTTP server serving a synthetic encrypted container. Tests assert plaintext SHA-256 and cache proof, preserved LOGIN classification on platform denial, account-change cancellation and absence of partial output. **There is no real TLS handshake or external KuGou call in these tests.** Request cancellation during a blocking socket operation still waits for existing connection/read timeouts; it is checked again before accepting a response or publishing output.

Also reruns: 120 cipher/container assertions, 35 KGG integration assertions, 24 protocol scenarios, 18 manager/parser scenarios, 8 Island state combinations and 80 disabled cycles; full-screen/module source wiring. MusicScreen and game/native hosts are not fully compiled/executed by this focused harness. Synthetic FLAC-marker patterns are not playable real music.

No live standard/concept account, real matching KGG/eKey, native decoding, audible playback, full Gradle build, Minecraft/Pojav or GPU test was performed. Failure to obtain a real eKey may still occur when platform entitlement, independent vip_token, metadata or verification is required. These cases are not bypassed.

## Package audit

```sh
python3 scripts/verify081/audit-package.py \
  /path/to/Pojavbouncenew-Nextgen-080-NEORAVEN-COMPILE-FIX.zip \
  /path/to/Pojavbouncenew-Nextgen-081-KGG-ONLINE-KEYS.zip
```

Requires the immutable delivered080 baseline SHA-256. Only four existing music files may differ; only the new online key source, 081 docs/references/tests may be added. Every other baseline byte must be preserved, with no deleted baseline file. Rejects unexpected working-tree files except generated .gradle/.kotlin/build/cache artifacts; these are excluded from the source archive. CRC, flat-root, file set and every packaged byte are reread and verified. Produces scope-audit.json, the ZIP, sidecar SHA-256 and /home/user/081-delivery.json.
