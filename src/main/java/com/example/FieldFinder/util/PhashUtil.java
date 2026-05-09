package com.example.FieldFinder.util;

import dev.brachtendorf.jimagehash.hash.Hash;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public final class PhashUtil {

    private static final PerceptiveHash HASHER = new PerceptiveHash(64);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private PhashUtil() {}

    public static Long computeFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            Hash h = HASHER.hash(img);
            return hashToLong(h);
        } catch (IOException e) {
            return null;
        }
    }

    public static Long computeFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        String clean = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            byte[] bytes = Base64.getDecoder().decode(clean);
            return computeFromBytes(bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Long computeFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            return computeFromBytes(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long hashToLong(Hash h) {
        BigInteger bi = h.getHashValue();
        // Take low 64 bits; pHash 64-bit fits.
        return bi.longValue();
    }
}