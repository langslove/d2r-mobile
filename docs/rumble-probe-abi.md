# Matching binary ABI evidence for B

中文：[rumble-probe-abi.zh-CN.md](rumble-probe-abi.zh-CN.md)

The existing user's <import-package>/out patched main/sdk images, dynamic symbols and sidecar TSVs were inspected read-only. No game binaries, full disassembly or resources are committed. Product is 3.3.94012-r45. As in loader.c, MOD0 leads to dynamic SYMTAB/STRTAB; JMPREL maps game imports/GOT/PLT, followed by BL callers. AArch64 ELF snippets existed only in memory and were passed to NDK llvm-objdump stdin, without intermediate files.

## Identity and scope

| Module | cuts.tsv BUILD_ID | patched image SHA-256 |
|---|---|---|
| main | db0507f36b84200ac05f75d76ed5026f | 976ced41d8ecae391937d35a577ed044aac1668f6a4043e0b71f83c0549bbd27 |
| sdk | 2d369d9999e3e10e665106f840ffff9b | ffe988402b339053c60f8ddb294073248f82b1e66db3bc4a64ab5c6e92f07b7c |

The main build ID matches patch_nso.py RULE_BUILD. Audited SDK ranges 0x225190–0x2254a0 and 0x22a630–0x22a8e8, and game ranges 0x1b64154–0x1b64218 and 0x1b64dd0–0x1b64ef4 contain no sites/cuts rewrites. These are unchanged functions within matching patched images, not claimed original-NSO whole-file hashes.


## Contracts

SDK IsVibrationPermitted at 0x225190 (80 bytes) takes no arguments; its detail writes a bool and the public wrapper returns it via ldurb w0. The host unsigned wrapper is retained; B returns one. Game 0x1b64df4 checks bit zero and skips the send path when false.

SDK GetVibrationDeviceHandles at 0x225280 (224 bytes) saves x0 output, signed w1 capacity, x2 ID reference and w3 by-value BitFlagSet<32>. It loads the ID from x2, calls the utility, and on success returns the count from a stack slot in w0. The public return is a count, not Result.

Initialize at 0x225360 (68 bytes) takes a four-byte handle reference in x0; detail 0x21fc48 loads it as w1. Send at 0x225430 (68 bytes) takes handle and value references in x0/x1; detail 0x21fdd8 loads the handle as w2 and forwards the value pointer. Both public wrappers handle internal errors and expose no output used by the game; existing void declarations remain.

Game argument loading was inspected near 0x1b64154, 0x1b641e0, 0x1b644bc, 0x1b6475c and 0x1b64ea8. Initialization increments the handle pointer by four bytes; send at 0x1b64ed4 passes that element in x0 and a sixteen-byte stack value in x1, again iterating four-byte handles. Main imports only these four related public vibration functions; this send path introduces no new device-info or batch-send call.

## Handles

MakeVibrationDeviceHandle at 0x22a6c0 (16 bytes) keeps the first argument's low eight bits, inserts player at bits 8–15 and logical device index at bits 16–23, with a zero high byte. Getters read bytes one and two.

Utility GetVibrationDeviceHandles at 0x22a6d0 (488 bytes), style-bit-zero branch 0x22a720, writes style index 3 as a uint32 and a second at output+4 with bit16 set when capacity permits, returning count two through its count pointer. Style-bit-one branch 0x22a768 uses index 4. Position conversion at 0x22a670 maps LRA logical indices 0/1 to left/right positions.

Thus host fullkey/id0/style1 uses 0x00000003 and 0x00010003; handheld/id0x20/style2 uses 0x00002004 and 0x00012004. B exposes only the currently connected host mode and truncates by capacity. This is SDK evidence, not inference from max=2 and not a claim about physical Pocket Ace motors. Other SDK modes are deliberately not advertised.

## Value and frequency

Game 0x1b64dfc zeros sixteen bytes. For nonzero inputs, 0x1b64e24/0x1b64e28 store float 1.0 at offsets 0/8, and 0x1b64e2c/0x1b64e30 store single-precision values at offsets 4/12, derived from inputs multiplied by 30/60. The zero path leaves the zero structure before sending.

This cross-checks the low amplitude/frequency/high amplitude/frequency order in [libnx HidVibrationValue](https://github.com/switchbrew/libnx/blob/master/nx/include/switch/services/hid.h). Host static assertions require sixteen bytes, four binary32 fields and offsets 0/4/8/12. Ordinary Itanium names do not encode return types; returns were not inferred from names alone.

This send path does not establish physical frequency maxima. No guessed limits or clamping are imposed. Negative/nonfinite frequencies are marked invalid; extreme finite values are preserved without asserting playability. Raw words are copied before formatting. There is no motor forwarding path.

## Unproved

Windows tests establish logic/boundaries, and ARM64 NDK fsyntax-only checks platform declarations. Neither establishes Pocket Ace permission for self process_vm_writev or sends in any particular scenario. A newly rebuilt B Debug APK must show permission one, enumeration results, init and sends on-device. Full HID IPC and every possible internal SDK path are outside scope; B has not been device-tested.
