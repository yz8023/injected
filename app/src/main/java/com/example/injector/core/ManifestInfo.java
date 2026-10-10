package com.example.injector.core;

import java.util.ArrayList;
import java.util.List;

public class ManifestInfo {

    public String packageName;
    public final List<String> launcherTargets = new ArrayList<>();

    public String resolveClassName(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.startsWith(".")) {
            return (packageName == null ? "" : packageName) + raw;
        }
        if (!raw.contains(".")) {
            return (packageName == null ? "" : packageName + ".") + raw;
        }
        return raw;
    }
}
