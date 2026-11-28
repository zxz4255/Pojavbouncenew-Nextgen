# 079 KGG source provenance / attribution

## Aaron-gx/KGG-Decryptor (MIT)

Repository: https://github.com/Aaron-gx/KGG-Decryptor
Pinned commit: `82aac3a78d2a90921021b9bae1d486faae1a736e`

Saved unmodified upstream snapshots:
- `lib.rs`: `tauri-app/src-tauri/src/lib.rs` (eKey TEA derivation, QMC Map/RC4, KGG header, MMKV layout).
- `formats.rs`: `tauri-app/src-tauri/src/formats.rs` (KGM v3 cipher).
- `LICENSE`, `THIRD-PARTY-NOTICES.md`, `UPSTREAM-README.md`: root files.

Production `KggDecryptor.java` ports only those cipher/container parts to Java, with stricter bounds, TEA zero-tail validation, streaming, cancellation, codec validation and no-overwrite output. `KugouLocalFiles.kt` adapts explicit local JSON/MMKV key lookup and bounded import. No Tauri runtime, database decryption, account extraction, root code, monitor, or other formats were incorporated. Upstream notes Unlock Music algorithms as MIT; the upstream third-party notice is retained.

## rallait/kugou-kgg-decryptor (MIT)

Repository: https://github.com/rallait/kugou-kgg-decryptor
Pinned commit: `a8b32367b4d537903ecbdfbe777b52b839f08fee`

Saved unmodified snapshots (with `python-` filename prefix):
- `decrypt_my_audio.py`, `test_vectors.py`, `LICENSE` from repository root.
- `kgm_v3_sample.kgm`, `kgm_v3_sample.expected` from `tests/vectors/`.

Used as an independent Python cross-check for Java eKey derivation, Map/RC4 stream transformations, golden masks and the short public v3 test fixture. The full Python scripts are **not production dependencies and are not invoked by the player**. Fixture generator extracts only selected AST nodes (no database, CLI, crypto dependency initialization or network activity); synthetic test eKeys are deterministic fake fixture data, never live account material.

These are pinned source references, not a claim that GitHub code is independently audited or that all real KGG versions work. No user KGG/eKey was supplied. See `079-KGG-DECRYPTION.md` for exact limitations.

## License handling

MIT copyright/license texts and upstream third-party notices are retained here. Adapted implementation files identify their sources. This project's existing GPL-3.0-or-later license remains unchanged. Do not remove the retained MIT notices when redistributing the port.
