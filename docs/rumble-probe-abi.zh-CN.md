# 模式 B 的匹配二进制 ABI 证据

English: [rumble-probe-abi.md](rumble-probe-abi.md)

本次只读分析了用户现有的 `<import-package>/out/main.patched.bin`、`sdk.patched.bin`、符号/动态表及配套 TSV；没有导出或提交游戏二进制、完整反汇编或资源。产品代号为 3.3.94012-r45。分析方法与 loader.c 一致：MOD0 → dynamic → DT_SYMTAB/DT_STRTAB；通过 JMPREL 定位 main 的导入/GOT/PLT，然后查 BL 调用点。片段仅在内存中包装为 AArch64 ELF，交给现有 NDK llvm-objdump 从标准输入反汇编，不生成中间文件。

## 身份与证据边界

| 模块 | 配套 cuts.tsv 的 BUILD_ID | patched.bin SHA-256 |
|---|---|---|
| main | db0507f36b84200ac05f75d76ed5026f | 976ced41d8ecae391937d35a577ed044aac1668f6a4043e0b71f83c0549bbd27 |
| sdk | 2d369d9999e3e10e665106f840ffff9b | ffe988402b339053c60f8ddb294073248f82b1e66db3bc4a64ab5c6e92f07b7c |

main 的 BUILD_ID 与 patch_nso.py 的 RULE_BUILD 一致。审计的 SDK 区间 0x225190–0x2254a0、0x22a630–0x22a8e8，以及 main 初始化/发送区间 0x1b64154–0x1b64218、0x1b64dd0–0x1b64ef4，在 sites/cuts 表中均没有重写点。证据来自已匹配的改写映像内未改写的函数，不冒充原始 NSO 的整体哈希。


## 已确认的契约

| SDK 地址 | 结论 |
|---|---|
| 0x225190，IsVibrationPermitted，80 字节 | 无输入参数；detail 写出一个 bool，公开包装用 ldurb 取到 w0 返回。保留宿主 unsigned 返回封装，B 返回 1。main+0x1b64df4 检查 bit0，不允许则跳过整段发送 |
| 0x225280，GetVibrationDeviceHandles，224 字节 | x0 输出数组，w1 有符号最大数量，x2 为 uint32 ID 引用，w3 是 BitFlagSet<32> 按值。包装读取 [x2]，向内部工具传参，成功后从栈上数量槽加载 w0；返回数量，不是 Result |
| 0x225360，InitializeVibrationDevice，68 字节 | x0 是 4 字节句柄引用；detail+0x21fc48 以 ldr w1 读取。公开层处理内部失败后无输出；游戏初始化循环不使用返回值 |
| 0x225430，SendVibrationValue，68 字节 | x0 句柄引用、x1 值结构引用；detail+0x21fdd8 读句柄 w2，再把值指针交给服务。公开层处理 Result 后无输出，游戏不使用其返回值 |

main+0x1b64154、0x1b641e0、0x1b644bc、0x1b6475c、0x1b64ea8 附近核对了枚举参数装载。初始化循环每次加 4 字节；main+0x1b64ed4 发送时 x0 指向该四字节元素，x1 指向栈上 16 字节的值，随后同样按 4 字节遍历句柄。main 只导入这四个相关公共震动函数；这条游戏发送路径不新增设备信息查询或批量发送的调用。

## 合法虚拟句柄

SDK utility MakeVibrationDeviceHandle（0x22a6c0，16 字节）先取参数 0 的低 8 位，再把玩家号放在 bit8..15、逻辑设备号放在 bit16..23，最高字节为零。SDK getter 分别读取 byte1、byte2。

utility GetVibrationDeviceHandles（0x22a6d0，488 字节）中，style bit0 分支（0x22a720）生成 style index 3，先写第一个 uint32；容量至少 2 时在输出+4 写同一编码加 bit16，数量变为 2。style bit1 分支（0x22a768）使用 index 4。utility 的位置转换函数 0x22a670 将 LRA 设备 index 0/1 映射为左/右逻辑位置。

因此当前宿主支持的两种模式为：

- fullkey，id=0，style=1：0x00000003、0x00010003。
- handheld，id=0x20，style=2：0x00002004、0x00012004。

B 只提供当前 connected_id()/style_of() 的这一对虚拟逻辑设备，并按容量截断；不是从日志 max=2 猜数量，不表示 Pocket Ace 的物理马达数量。SDK 还支持其他 id/style，但本实现不广告宿主没有的设备。

## 四个 float 与频率范围

main+0x1b64dfc 先把 16 字节置零；非零输入路径在 +0x1b64e24/+0x1b64e28 写 float 1.0 到偏移 0/8，在 +0x1b64e2c/+0x1b64e30 写单精度值到偏移 4/12，后两者来自输入乘 30/60。零输入路径保持全零，再传给 SendVibrationValue。

这与 [libnx 的 HidVibrationValue](https://github.com/switchbrew/libnx/blob/master/nx/include/switch/services/hid.h) 的幅度低/频率低/幅度高/频率高定义交叉一致。16 字节、四个 binary32、偏移 0/4/8/12 在宿主编译时也做静态断言。公开普通 Itanium 名字不携带返回类型，所以没有仅凭符号推断返回值。

未从该发送路径证明物理频率上限，不以公开常见范围强制钳制。负频率/非有限值被诊断为 invalid；非常大的有限频率原样记录，不声称硬件能播放。所有 raw 位模式在任何浮点格式化之前复制。B 没有输出马达，所以不存在错误参数被转发的路径。

## 尚未证明

Windows 测试证明逻辑和边界，NDK fsyntax-only 证明目标平台声明可编译；它们不能证明 Pocket Ace 允许 self process_vm_writev，也不能证明游戏在某场景一定发送。下一轮重编 Debug APK 后，需要由实际 B 日志确认许可=1、returned 数量、初始化与 send。未实现完整 HID IPC，不拦截 SDK 内一切可能路径，也未运行 B 实机。
