# Licensing and source provenance — v0.6

## Chosen license: GNU GPL version 3

O2P Control Center is already distributed from its GitHub repository under the [GNU GPL v3.0](../LICENSE). **No license change is proposed for v0.6.** The repository's owners should keep copyright notices accurate. This file does not provide legal advice or grant rights that source authors did not license.

## Technical reference projects

- **ClusterTune (AurelioB)**: current `main` license is MIT; `LICENSE` identifies Aurelio Bernal © 2026. Some historical ClusterTune revisions were GPLv2 and are **not automatically covered by the current MIT license**. We studied current architecture, the PServer host/transaction model, and frequency discovery as reference material. Any future *direct code reuse* from current MIT sources must preserve the MIT text and applicable notices and verify file provenance.
- **PULSE (keiretrogaming)**: repository currently declares GNU GPL v2.0. Studied only for conceptual understanding of PServer service commands, Adreno GPU power-level mapping, risks from vendor-daemon minimum floors, and manual profile UI. We did **not** transplant GPLv2 code from PULSE to O2P. GPLv2-only implementation code must not be mixed directly into a GPLv3-only combined work absent suitable permission or a compatible license grant.
- Android, Compose, Kotlin, AndroidX, coroutines and JUnit are built from their respective dependencies; their own license notices should be tracked separately for a redistribution release.

The O2P v0.6 preview frequency parser, saved-profile representation, editor and tests were independently authored here against the app's pre-existing `DeviceSnapshot` read-only diagnostics model, without code copied from PULSE/ClusterTune. The existing `PServerReader` code is still *unverified on the Portal*; it only performs read operations, and the privilege capability does not imply authorization to write.

## Release checklist

- [ ] Confirm ownership and provenance of the original O2P project code and any inherited `O2PKernelTurb` snippets.
- [ ] Inventory third-party dependencies, fonts, icons and assets, including their redistribution terms.
- [ ] If importing MIT-licensed ClusterTune code, retain exact copyright/license notice and relevant source attribution.
- [ ] Avoid using GPLv2-only PULSE sources directly. If necessary, seek a legally compatible grant first.
- [ ] Add SPDX identifiers and correct copyright notices for files owned by the relevant contributors after verification.
- [ ] Include source offer/availability and a GPLv3 license text with distributed binaries.

Please consult qualified legal counsel if the app will be commercially distributed or if the licensing/provenance of a specific source file is uncertain.
