package com.htmltoapk.builder;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Escreve um ZIP/APK; entradas "stored" ficam alinhadas em 4 bytes (exigido p/ resources.arsc). */
final class ZipWriter implements Closeable {
    private static final class Counter extends FilterOutputStream {
        long count;
        Counter(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    private final Counter counter;
    private final ZipOutputStream zip;
    private final Set<String> names = new HashSet<>();

    ZipWriter(OutputStream out) {
        counter = new Counter(new BufferedOutputStream(out, 1 << 16));
        zip = new ZipOutputStream(counter);
    }

    void putStored(String name, byte[] data) throws IOException {
        if (!names.add(name)) return;
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        int pad = (int) ((4 - ((counter.count + 30 + nameLen) % 4)) % 4);
        if (pad > 0) e.setExtra(new byte[pad]);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    void putDeflated(String name, byte[] data) throws IOException {
        if (!names.add(name)) return;
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    void putDeflated(String name, InputStream in) throws IOException {
        if (!names.add(name)) return;
        zip.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[1 << 15];
        int n;
        while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        zip.closeEntry();
    }

    @Override public void close() throws IOException { zip.close(); }
}
