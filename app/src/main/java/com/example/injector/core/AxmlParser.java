package com.example.injector.core;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

public class AxmlParser {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int UTF8_FLAG = 0x100;
    private static final int VALUE_TYPE_STRING = 0x03;
    private static final int NO_INDEX = 0xFFFFFFFF;

    private final byte[] data;
    private String[] strings;

    public AxmlParser(byte[] data) {
        this.data = data;
    }

    public ManifestInfo parse() throws Exception {
        if (data.length < 8 || u16(0) != 0x0003) {
            throw new IllegalStateException("非 AXML 文档");
        }
        int stringCount = 0;
        int stringOffsetsAt = 0;
        int stringsStart = 0;
        int poolStart = 0;
        boolean utf8 = false;

        int pos = 8;
        int firstTagPos = -1;
        while (pos + 8 <= data.length) {
            int type = u16(pos);
            int headerSize = u16(pos + 2);
            int size = s32(pos + 4);
            if (size <= 0 || pos + size > data.length) break;

            if (type == RES_STRING_POOL_TYPE) {
                stringCount = s32(pos + 8);
                int flags = s32(pos + 16);
                stringsStart = s32(pos + 20);
                stringOffsetsAt = pos + headerSize;
                poolStart = pos;
                utf8 = (flags & UTF8_FLAG) != 0;
            } else if (type == RES_XML_START_ELEMENT_TYPE) {
                firstTagPos = pos;
                break;
            }
            pos += size;
        }
        if (firstTagPos < 0) throw new IllegalStateException("AXML 缺少元素数据");
        ensureStrings(stringCount, stringOffsetsAt, stringsStart, utf8, poolStart);
        return parseDocument(firstTagPos);
    }

    private ManifestInfo parseDocument(int pos) throws Exception {
        ManifestInfo info = new ManifestInfo();
        String current = null;
        boolean currentIsAlias = false;
        String currentTarget = null;
        boolean sawMain = false;
        boolean inActivity = false;

        while (pos + 8 <= data.length) {
            int type = u16(pos);
            int size = s32(pos + 4);
            if (size <= 0 || pos + size > data.length) break;

            if (type == RES_XML_START_ELEMENT_TYPE) {
                int ext = pos + 16;
                String name = str(s32(ext + 4));
                int attrStart = u16(ext + 8);
                int attrSize = u16(ext + 10);
                int attrCount = u16(ext + 12);

                String androidName = null;
                String androidTarget = null;
                String plainPackage = null;

                for (int i = 0; i < attrCount; i++) {
                    int a = ext + attrStart + i * attrSize;
                    int nsIdx = s32(a);
                    int nameIdx = s32(a + 4);
                    int rawIdx = s32(a + 8);
                    int dataType = data[a + 15] & 0xFF;
                    int dataVal = s32(a + 16);
                    String attrNs = nsIdx == NO_INDEX ? null : str(nsIdx);
                    String attrName = str(nameIdx);
                    String value = attrValue(rawIdx, dataType, dataVal);
                    boolean isAndroid = ANDROID_NS.equals(attrNs);
                    if (isAndroid && "name".equals(attrName)) androidName = value;
                    else if (isAndroid && "targetActivity".equals(attrName)) androidTarget = value;
                    else if (attrNs == null && "package".equals(attrName)) plainPackage = value;
                }

                if ("manifest".equals(name)) {
                    info.packageName = plainPackage;
                } else if ("activity".equals(name) || "activity-alias".equals(name)) {
                    inActivity = true;
                    currentIsAlias = "activity-alias".equals(name);
                    current = androidName;
                    currentTarget = androidTarget;
                    sawMain = false;
                } else if (inActivity && "action".equals(name)) {
                    if ("android.intent.action.MAIN".equals(androidName)) sawMain = true;
                } else if (inActivity && "category".equals(name)) {
                    if ("android.intent.category.LAUNCHER".equals(androidName)
                            && sawMain && current != null) {
                        String target = currentIsAlias ? currentTarget : current;
                        if (target != null) {
                            String full = info.resolveClassName(target);
                            if (!info.launcherTargets.contains(full)) {
                                info.launcherTargets.add(full);
                            }
                        }
                    }
                }
            } else if (type == RES_XML_END_ELEMENT_TYPE) {
                String name = str(s32(pos + 16 + 4));
                if ("activity".equals(name) || "activity-alias".equals(name)) {
                    inActivity = false;
                    current = null;
                    currentIsAlias = false;
                    currentTarget = null;
                    sawMain = false;
                }
            }
            pos += size;
        }
        return info;
    }

    private void ensureStrings(int count, int offsetsAt, int stringsStart, boolean utf8, int chunkStart)
            throws UnsupportedEncodingException {
        strings = new String[Math.max(count, 0)];
        int base = chunkStart + stringsStart;
        for (int i = 0; i < count; i++) {
            int off = base + s32(offsetsAt + i * 4);
            if (utf8) {
                int p = off;
                int skip = data[p] & 0xFF;
                if ((skip & 0x80) != 0) p += 2; else p += 1;
                int len = data[p] & 0xFF;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7F) << 8) | (data[p + 1] & 0xFF);
                    p += 2;
                } else {
                    p += 1;
                }
                strings[i] = new String(data, p, len, "UTF-8");
            } else {
                int p = off;
                int len = u16(p);
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7FFF) << 16) | u16(p + 2);
                    p += 4;
                } else {
                    p += 2;
                }
                StringBuilder sb = new StringBuilder(len);
                for (int c = 0; c < len; c++) {
                    sb.append((char) u16(p + c * 2));
                }
                strings[i] = sb.toString();
            }
        }
    }

    private String attrValue(int rawIdx, int dataType, int dataVal) {
        if (rawIdx != NO_INDEX) {
            String raw = str(rawIdx);
            if (raw != null) return raw;
        }
        if (dataType == VALUE_TYPE_STRING) return str(dataVal);
        if (dataType == 0x10) return String.valueOf(dataVal);
        if (dataType == 0x12) return dataVal != 0 ? "true" : "false";
        return String.valueOf(dataVal);
    }

    private int u16(int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private int s32(int off) {
        return (data[off] & 0xFF)
                | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private String str(int idx) {
        if (idx < 0 || idx >= strings.length) return null;
        return strings[idx];
    }
}
