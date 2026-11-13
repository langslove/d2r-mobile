package com.xiaoboluo.d2r;

// 硬件测试与模式 C 共用身份策略，设备号和振动器号不属于固定规则。
// Shared by hardware tests and C; input/vibrator IDs are never fixed identity rules.
final class ControllerIdentity {
    static String mode(String name, int vendor, int product, String descriptor) {
        if (descriptor == null || descriptor.trim().isEmpty()) return null;
        if ("AYANEO Controller".equals(name) && vendor == 0x4001 && product == 0x0428) return "AYANEO";
        if ("Microsoft X-Box 360 pad".equals(name) && vendor == 0x045e && product == 0x028e) return "XBOX360";
        return null;
    }
    static boolean same(String expectedMode, String expectedDescriptor, String name, int vendor, int product, String descriptor) {
        return expectedMode != null && expectedMode.equals(mode(name, vendor, product, descriptor))
                && expectedDescriptor != null && expectedDescriptor.equals(descriptor);
    }
}
