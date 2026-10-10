package com.example.injector.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipSafety {

    private ZipSafety() {
    }

    public static void unzip(InputStream in, File targetDir) throws IOException {
        File base = targetDir.getCanonicalFile();
        String basePrefix = base.getPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                try {
                    File f = new File(base, e.getName());
                    String canonical = f.getCanonicalPath();
                    if (!canonical.equals(base.getPath()) && !canonical.startsWith(basePrefix)) {
                        continue; // zip 条目路径越界，跳过
                    }
                    if (e.isDirectory()) {
                        f.mkdirs();
                        continue;
                    }
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                    }
                } catch (IOException badEntry) {
                    // 非标 zip（如 STORED+EXT descriptor）个别条目损坏：跳过该条目继续
                } finally {
                    zis.closeEntry();
                }
            }
        }
    }
}
