package com.example.injector.core;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class AlignedZipWriter implements Closeable {

    private static final int LFH_SIG = 0x04034b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int DD_SIG = 0x08074b50;
    private static final int FLAG_DATA_DESCRIPTOR = 0x0008;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    private final OutputStream out;
    private long offset = 0;
    private final List<CdRecord> records = new ArrayList<>();
    private final Map<String, long[]> offsetsByName = new HashMap<>();
    private final Map<String, Integer> methodsByName = new HashMap<>();

    public AlignedZipWriter(OutputStream out) {
        this.out = out;
    }

    public void writeStored(String name, long mtimeMillis, int alignment,
                            InputStream in, long size, long crc) throws IOException {
        if (alignment < 1) alignment = 1;
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int[] dos = dosTime(mtimeMillis);
        int pad = paddingFor(offset + 30 + nameBytes.length, alignment);

        long lfhOffset = offset;
        writeLfHeader(nameBytes, pad, METHOD_STORED, 0, crc, size, size, dos);
        copyFixed(in, out, size);
        offset += size;
        offsetsByName.put(name, new long[]{lfhOffset, lfhOffset + 30 + nameBytes.length + pad});
        methodsByName.put(name, METHOD_STORED);
        records.add(new CdRecord(nameBytes, METHOD_STORED, 0, crc, size, size, lfhOffset, dos));
    }

    public void writeDeflated(String name, long mtimeMillis, InputStream in) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int[] dos = dosTime(mtimeMillis);
        int pad = paddingFor(offset + 30 + nameBytes.length, 4);

        CRC32 crc = new CRC32();
        CountingSink sink = new CountingSink(out);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        long uncompSize = 0;

        long lfhOffset = offset;
        writeLfHeader(nameBytes, pad, METHOD_DEFLATED, FLAG_DATA_DESCRIPTOR, 0, 0, 0, dos);
        try (DeflaterOutputStream dos2 = new DeflaterOutputStream(sink, deflater, buf.length, true)) {
            byte[] buf2 = new byte[65536];
            int n;
            while ((n = in.read(buf2)) > 0) {
                crc.update(buf2, 0, n);
                uncompSize += n;
                dos2.write(buf2, 0, n);
            }
            dos2.finish();
        } finally {
            deflater.end();
        }
        long compSize = sink.count;
        offset += compSize;
        writeDd(crc.getValue(), compSize, uncompSize);
        offsetsByName.put(name, new long[]{lfhOffset, lfhOffset + 30 + nameBytes.length + pad});
        methodsByName.put(name, METHOD_DEFLATED);
        records.add(new CdRecord(nameBytes, METHOD_DEFLATED, FLAG_DATA_DESCRIPTOR,
                crc.getValue(), compSize, uncompSize, lfhOffset, dos));
    }

    public long dataOffset(String name) {
        long[] v = offsetsByName.get(name);
        return v == null ? -1 : v[1];
    }

    public int methodOf(String name) {
        Integer m = methodsByName.get(name);
        return m == null ? -1 : m;
    }

    @Override
    public void close() throws IOException {
        long cdStart = offset;
        ByteArrayOutputStream cd = new ByteArrayOutputStream();
        for (CdRecord r : records) {
            w32(cd, CD_SIG);
            w16(cd, 20);
            w16(cd, 20);
            w16(cd, r.flags);
            w16(cd, r.method);
            w16(cd, r.dosTime);
            w16(cd, r.dosDate);
            w32(cd, r.crc);
            w32(cd, r.compSize);
            w32(cd, r.uncompSize);
            w16(cd, r.name.length);
            w16(cd, 0);
            w16(cd, 0);
            w16(cd, 0);
            w16(cd, 0);
            w32(cd, 0);
            w32(cd, r.lfhOffset);
            cd.write(r.name);
        }
        byte[] cdBytes = cd.toByteArray();
        w32(out, EOCD_SIG);
        w16(out, 0);
        w16(out, 0);
        w16(out, records.size());
        w16(out, records.size());
        w32(out, cdBytes.length);
        w32(out, cdStart);
        w16(out, 0);
        offset = cdStart + cdBytes.length + 22;
        out.flush();
    }

    private final byte[] buf = new byte[65536];

    private void writeLfHeader(byte[] nameBytes, int extraLen, int method, int flags,
                               long crc, long compSize, long uncompSize, int[] dos) throws IOException {
        w32(out, LFH_SIG);
        w16(out, 20);
        w16(out, flags);
        w16(out, method);
        w16(out, dos[0]);
        w16(out, dos[1]);
        w32(out, crc);
        w32(out, compSize);
        w32(out, uncompSize);
        w16(out, nameBytes.length);
        w16(out, extraLen);
        out.write(nameBytes);
        if (extraLen > 0) {
            out.write(ZERO_PAD, 0, Math.min(extraLen, ZERO_PAD.length));
            for (int i = ZERO_PAD.length; i < extraLen; i++) out.write(0);
        }
        offset += 30 + nameBytes.length + extraLen;
    }

    private void writeDd(long crc, long compSize, long uncompSize) throws IOException {
        w32(out, DD_SIG);
        w32(out, crc);
        w32(out, compSize);
        w32(out, uncompSize);
        offset += 16;
    }

    private void copyFixed(InputStream in, OutputStream dst, long expected) throws IOException {
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            dst.write(buf, 0, n);
            total += n;
        }
        if (total != expected) {
            throw new IOException("STORED 条目实际大小 " + total + " 与预期 " + expected + " 不符");
        }
    }

    private static int paddingFor(long baseOffset, int alignment) {
        long r = baseOffset % alignment;
        return (int) ((alignment - r) % alignment);
    }

    private static int[] dosTime(long millis) {
        Calendar c = Calendar.getInstance();
        if (millis > 0) c.setTimeInMillis(millis);
        int year = c.get(Calendar.YEAR);
        if (year < 1980) year = 1980;
        int time = (c.get(Calendar.SECOND) / 2)
                | (c.get(Calendar.MINUTE) << 5)
                | (c.get(Calendar.HOUR_OF_DAY) << 11);
        int date = c.get(Calendar.DAY_OF_MONTH)
                | ((c.get(Calendar.MONTH) + 1) << 5)
                | ((year - 1980) << 9);
        return new int[]{time, date};
    }

    private static void w16(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void w32(OutputStream out, long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
    }

    private static final byte[] ZERO_PAD = new byte[4096];

    private static final class CdRecord {
        final byte[] name;
        final int method;
        final int flags;
        final long crc;
        final long compSize;
        final long uncompSize;
        final long lfhOffset;
        final int dosTime;
        final int dosDate;

        CdRecord(byte[] name, int method, int flags, long crc, long compSize,
                 long uncompSize, long lfhOffset, int[] dos) {
            this.name = name;
            this.method = method;
            this.flags = flags;
            this.crc = crc;
            this.compSize = compSize;
            this.uncompSize = uncompSize;
            this.lfhOffset = lfhOffset;
            this.dosTime = dos[0];
            this.dosDate = dos[1];
        }
    }

    private static final class CountingSink extends OutputStream {
        final OutputStream delegate;
        long count;

        CountingSink(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }
    }
}
