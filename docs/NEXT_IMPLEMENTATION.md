# Next implementation milestone (post-hardware-validation)

M2: typed, verified CPU/GPU maximum-frequency ceilings. Prerequisites:

- Verify CPU topology and GPU available OPPs using the exported Hardware Explorer report.
- Snapshot original max values for **every node intended for modification** with firmware fingerprint and timestamp.
- Validate path is a discovered allowlisted CPU `policyN/scaling_max_freq` or verified GPU max node; clamp user choice to enumerated frequencies and OEM allowed ceiling.
- Serialize writes and parse an explicit exit marker; read back each node independently and classify as APPLIED, CLAMPED, OVERRIDDEN, FAILED or UNVERIFIED.
- Restore **only nodes that still contain the app's last verified value** and only to that session's recorded baseline. Skip OEM-owned/external changes and disclose unresolved nodes.
- Never automatically raise above captured ceiling, change governors/CPU affinity, or disable thermal controls.
- Establish a watchdog mechanism and recovery guarantees *before* enabling long-running AutoPilot.
- Include a conflict warning for ClusterTune and other tuners; never terminate them automatically.
- Add AYN thermal-state-aware suspension and monotonic controller timing.

AutoPilot cannot be responsibly enabled by hardcoding hypothetical frequencies or fan paths from another Odin model.
