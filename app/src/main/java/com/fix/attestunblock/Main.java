package com.fix.attestunblock;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 解除 PixelExperience/AOSP 系 ROM 内置的密钥认证拦截。
 *
 * ROM 的 framework.jar 里 com.android.internal.util.custom.PixelPropsUtils
 * 会对 GMS / Play 商店(Finsky) 的 key attestation 直接抛 UnsupportedOperationException
 * （日志 "Blocked key attestation"），导致认证请求到不了 keystore2，
 * TrickyStore + keybox 无从发挥，Play Integrity = NO_INTEGRITY。
 *
 * 本模块 hook 掉那个拦截方法，让认证请求正常穿过框架到达 keystore2，
 * 由 TrickyStore 用 keybox 伪造硬件认证。
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "[AttestUnblock] ";
    private static final String CLS = "com.android.internal.util.custom.PixelPropsUtils";
    private static final String METHOD = "onEngineGetCertificateChain";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = lpparam.classLoader.loadClass(CLS);
        } catch (Throwable ignored) {
            return; // 该进程里没有这个类，忽略
        }

        int hooked = 0;
        for (final Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(METHOD)) continue;
            final Class<?> ret = m.getReturnType();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 在拦截逻辑运行前直接给出结果，跳过它的 throw
                        if (ret == void.class) {
                            param.setResult(null);              // 原方法返回 void：什么都不做
                        } else if (param.args != null && param.args.length > 0) {
                            param.setResult(param.args[0]);     // 原方法 (chain)->chain：原样放行真实/TrickyStore 证书链
                        } else {
                            param.setResult(null);
                        }
                    }
                });
                hooked++;
                XposedBridge.log(TAG + "hooked " + m + " in " + lpparam.packageName);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "hook failed: " + t);
            }
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + "no " + METHOD + " found in " + lpparam.packageName);
        }
    }
}
