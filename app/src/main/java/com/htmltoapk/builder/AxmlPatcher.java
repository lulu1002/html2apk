package com.htmltoapk.builder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Edita strings do AndroidManifest.xml binário (AXML) trocando entradas do
 * string pool pelo mesmo índice. Como os índices não mudam, o resto do arquivo
 * continua válido. Só precisa refazer o pool e os tamanhos dos chunks.
 */
public final class AxmlPatcher {
    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int FLAG_UTF8 = 0x100;

    private AxmlPatcher() {}

    public static byte[] replaceStrings(byte[] src, Map<String, String> repl) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN);
        if (src.length < 36 || (in.getShort(0) & 0xFFFF) != CHUNK_XML) {
            throw new IOException("AndroidManifest binário inválido");
        }
        int poolOff = in.getShort(2) & 0xFFFF;
        if ((in.getShort(poolOff) & 0xFFFF) != CHUNK_STRING_POOL) {
            throw new IOException("String pool não encontrado");
        }
        int poolHeader = in.getShort(poolOff + 2) & 0xFFFF;
        int poolSize = in.getInt(poolOff + 4);
        int strCount = in.getInt(poolOff + 8);
        int styleCount = in.getInt(poolOff + 12);
        int flags = in.getInt(poolOff + 16);
        int strStart = in.getInt(poolOff + 20);
        if (styleCount != 0) throw new IOException("Manifest com estilos não suportado");
        boolean utf8 = (flags & FLAG_UTF8) != 0;

        int offsetsBase = poolOff + poolHeader;
        int dataBase = poolOff + strStart;
        String[] s = new String[strCount];
        for (int i = 0; i < strCount; i++) {
            int off = in.getInt(offsetsBase + 4 * i);
            s[i] = utf8 ? readUtf8(src, dataBase + off) : readUtf16(src, dataBase + off);
            String r = repl.get(s[i]);
            if (r != null) s[i] = r;
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strCount];
        for (int i = 0; i < strCount; i++) {
            offs[i] = data.size();
            if (utf8) writeUtf8(data, s[i]); else writeUtf16(data, s[i]);
        }
        while (data.size() % 4 != 0) data.write(0);

        int newStrStart = poolHeader + 4 * strCount;
        int newPoolSize = newStrStart + data.size();
        int rest = poolOff + poolSize;
        int total = poolOff + newPoolSize + (src.length - rest);

        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(src, 0, poolOff);
        out.putInt(4, total);
        out.put(src, poolOff, poolHeader);
        out.putInt(poolOff + 4, newPoolSize);
        out.putInt(poolOff + 20, newStrStart);
        for (int i = 0; i < strCount; i++) out.putInt(offs[i]);
        out.put(data.toByteArray());
        out.put(src, rest, src.length - rest);
        return out.array();
    }

    private static String readUtf8(byte[] b, int p) {
        int len = b[p++] & 0xFF;
        if ((len & 0x80) != 0) len = ((len & 0x7F) << 8) | (b[p++] & 0xFF);
        int blen = b[p++] & 0xFF;
        if ((blen & 0x80) != 0) blen = ((blen & 0x7F) << 8) | (b[p++] & 0xFF);
        return new String(b, p, blen, StandardCharsets.UTF_8);
    }

    private static String readUtf16(byte[] b, int p) {
        int len = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        p += 2;
        if ((len & 0x8000) != 0) {
            int low = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
            len = ((len & 0x7FFF) << 16) | low;
            p += 2;
        }
        return new String(b, p, len * 2, StandardCharsets.UTF_16LE);
    }

    private static void writeLen8(ByteArrayOutputStream o, int n) throws IOException {
        if (n > 0x7FFF) throw new IOException("String grande demais");
        if (n > 0x7F) { o.write((n >> 8) | 0x80); o.write(n & 0xFF); } else o.write(n);
    }

    private static void writeUtf8(ByteArrayOutputStream o, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeLen8(o, s.length());
        writeLen8(o, bytes.length);
        o.write(bytes);
        o.write(0);
    }

    private static void writeUtf16(ByteArrayOutputStream o, String s) throws IOException {
        int n = s.length();
        if (n > 0x7FFF) {
            int hi = (n >> 16) | 0x8000;
            o.write(hi & 0xFF); o.write((hi >> 8) & 0xFF);
            o.write(n & 0xFF); o.write((n >> 8) & 0xFF);
        } else {
            o.write(n & 0xFF); o.write((n >> 8) & 0xFF);
        }
        o.write(s.getBytes(StandardCharsets.UTF_16LE));
        o.write(0); o.write(0);
    }
}
