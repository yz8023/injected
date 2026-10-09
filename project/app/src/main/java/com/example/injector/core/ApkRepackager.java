package com.example.injector.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkRepackager {

    private static boolean isSignatureEntry(String name) {
        if (!name.startsWith("META-INF/")) return false;
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.endsWith(".MF");
    }

    public static void repack(File apkIn, File patchedDex, String dexEntryName,
                              File popupDex, String popupDexName, File popupAssets,
                              File apkOut, List<String> log) throws Exception {
        try (ZipFile src = new ZipFile(apkIn);
             AlignedZipWriter w = new AlignedZipWriter(new FileOutputStream(apkOut))) {

            Enumeration<? extends ZipEntry> en = src.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.equals(dexEntryName) || name.equals(popupDexName)) continue;
                if (isSignatureEntry(name)) continue;

                if (e.isDirectory()) {
                    try (InputStream in = src.getInputStream(e)) {
                        w.writeStored(name, e.getTime(), 1, in, 0, 0L);
                    }
                    continue;
                }

                if ("resources.arsc".equals(name)) {
                    long[] m = measure(src, e);
                    try (InputStream in = src.getInputStream(e)) {
                        w.writeStored(name, e.getTime(), 4, in, m[0], m[1]);
                    }
                    continue;
                }

                if (e.getMethod() == ZipEntry.STORED) {
                    int align = name.endsWith(".so") ? 4096 : 4;
                    long[] m = measure(src, e);
                    try (InputStream in = src.getInputStream(e)) {
                        w.writeStored(name, e.getTime(), align, in, m[0], m[1]);
                    }
                } else {
                    try (InputStream in = src.getInputStream(e)) {
                        w.writeDeflated(name, e.getTime(), in);
                    }
                }
            }

            try (InputStream in = new FileInputStream(patchedDex)) {
                w.writeDeflated(dexEntryName, System.currentTimeMillis(), in);
            }
            if (popupDex != null && popupDex.exists()) {
                try (InputStream in = new FileInputStream(popupDex)) {
                    w.writeDeflated(popupDexName, System.currentTimeMillis(), in);
                }
            }
            if (popupAssets != null && popupAssets.exists()) {
                appendDir(w, popupAssets, "assets/");
            }
        }

        verifyProperties(apkIn, apkOut, popupDexName, log);
    }

    private static long[] measure(ZipFile zf, ZipEntry e) throws IOException {
        CRC32 crc = new CRC32();
        long size = 0;
        byte[] buf = new byte[65536];
        try (InputStream in = zf.getInputStream(e)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                size += n;
            }
        }
        return new long[]{size, crc.getValue()};
    }

    private static void appendDir(AlignedZipWriter w, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = prefix + f.getName();
            if (f.isDirectory()) {
                try (InputStream in = empty()) {
                    w.writeStored(name + "/", 0L, 1, in, 0, 0L);
                }
                appendDir(w, f, name + "/");
            } else {
                try (InputStream in = new FileInputStream(f)) {
                    w.writeDeflated(name, f.lastModified(), in);
                }
            }
        }
    }

    private static InputStream empty() {
        return new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
    }

    static void verifyProperties(File apkIn, File apkOut, String popupDexName, List<String> log) {
        try (ZipFile src = new ZipFile(apkIn);
             ZipFile out = new ZipFile(apkOut)) {

            int checked = 0;
            List<String> methodMismatches = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = out.entries();
            while (en.hasMoreElements()) {
                ZipEntry oe = en.nextElement();
                String name = oe.getName();
                if (name.equals(popupDexName) || name.startsWith("assets/")) continue;
                ZipEntry se = src.getEntry(name);
                if (se == null) continue;
                checked++;
                if (se.getMethod() != oe.getMethod()) {
                    methodMismatches.add(name);
                }
            }
            log.add("P1 压缩方式一致性：对比 " + checked + " 条，"
                    + (methodMismatches.isEmpty() ? "全部一致" : "异常条目 " + methodMismatches));

            ZipEntry arsc = out.getEntry("resources.arsc");
            if (arsc == null) {
                log.add("P2 resources.arsc：产物中缺失");
            } else {
                boolean stored = arsc.getMethod() == ZipEntry.STORED;
                long dataOffset = localDataOffset(apkOut, arsc);
                boolean aligned = dataOffset >= 0 && dataOffset % 4 == 0;
                log.add("P2 resources.arsc：" + (stored ? "STORED" : "被压缩")
                        + "，数据偏移 " + dataOffset + "（" + (aligned ? "4 字节对齐" : "未对齐") + "）");
            }

            boolean oldSig = false;
            Enumeration<? extends ZipEntry> en2 = out.entries();
            while (en2.hasMoreElements()) {
                String n = en2.nextElement().getName();
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                        || n.endsWith(".DSA") || n.endsWith(".MF"))) {
                    oldSig = true;
                    break;
                }
            }
            log.add(oldSig ? "旧签名条目：仍残留" : "旧签名条目：已剔除");
        } catch (Exception e) {
            log.add("产物属性自检异常：" + e);
        }
    }

    private static long localDataOffset(File zip, ZipEntry e) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(zip, "r")) {
            raf.seek(e.getHeaderOffset());
            byte[] head = new byte[30];
            raf.readFully(head);
            if ((head[0] & 0xFF) != 0x50 || (head[1] & 0xFF) != 0x4B) return -1;
            int nameLen = (head[26] & 0xFF) | ((head[27] & 0xFF) << 8);
            int extraLen = (head[28] & 0xFF) | ((head[29] & 0xFF) << 8);
            return e.getHeaderOffset() + 30 + nameLen + extraLen;
        }
    }
}
