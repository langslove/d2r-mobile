# D2 regression tests

Run the standalone suite from the repository root with Python 3 and JDK 17 or newer:

```powershell
python dm/android/tools/tests/test_d2_active.py --jdk <JDK-directory>
```

The suite compiles the current Java output core and uses fixed synthetic input sequences in `RumbleDActiveTest.java`. It covers initial output, descending amplitude, zero, dual handles, timeout, sequence gaps, owner changes, lifecycle cancellation, stale snapshots and API failure. It uses a virtual clock and fake motor. No Android SDK, DLL, game data or device is required. Results go under the ignored Android build directory.

Historical replay is optional:

```powershell
python dm/android/tools/tests/test_d2_active.py --jdk <JDK-directory> --capture <capture-directory> --replay-lib <compatible-replay-library>
```

It requires `analysis-raw-events.jsonl`, `analysis-envelopes.csv`, and `d2-shadow-20261112-164857-live.log`, plus the compatible native replay library. These private historical artifacts are not distributed. Missing integration dependencies produce an explicit SKIP after the standalone suite. A skipped replay is not a passing replay. Historical replay previously validated six complete envelopes against the Python model. The standalone suite verifies software requests, not physical motor response.

The Android APK additionally requires the documented Android toolchain and locally generated game headers/assets. Those proprietary build inputs are not part of this standalone test.
