# Source migration audit

Original project: https://github.com/locutus5382/O2PKernelTurb

| Old behavior | New behavior |
|---|---|
| `PServerExec.runAsRoot()` treats missing output as `OK` | `ShellProtocol` requires checked exit marker |
| `su` ignores process exit code | `RootReader` checks exit code and times out |
| Generic arbitrary shell commands | Read-only `KernelPath` validation + `ReadTransport` |
| UI states `CONNECTED (uid=0)` without checking | Verified command `id -u` / `UNVERIFIED` |
| Hardware nodes display invented fallbacks | Missing nodes show unavailable and reason |
| `revertToStock()` sets guessed 512/60/64 values | No writes in alpha; future restore will snapshot actual values |
| Blind boot receiver with delayed task | No boot permission/receiver in alpha |
| Quick tile toggles kernel tweaks without verified writes | No tile until safe session management is implemented |
| Hardcoded `read_ahead=2048`, `swappiness=10` | Removed from default operation pending controlled benchmarks |

PServer API copied conceptually from source: lookup ServiceManager/PServerBinder, transact code 0, `Parcel.writeStringArray(arrayOf(command, "1"))`, response `Parcel.createByteArray()`. The payload is now limited to read-only probes; it is not trusted until a response marker is parsed.
