package com.exampro.service;

import com.exampro.model.entity.Response;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An encrypted copy of the student's answers on local disk, written on every
 * change. If the database is unreachable or the app dies between autosaves,
 * the next start of the same attempt reads this back and loses nothing.
 *
 * ponytail: the AES-GCM key is derived from the attempt id, so this keeps
 * answers out of casual view (a text editor, a file search) but is not
 * protection against someone who has the source. Upgrade: derive the key
 * from the student's password hash or the OS keystore.
 */
public class RecoveryCache {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;

    public RecoveryCache() {
        this(Path.of("data", "recovery"));
    }

    public RecoveryCache(Path dir) {
        this.dir = dir;
    }

    public void save(long attemptId, Collection<Response> responses) {
        try {
            Files.createDirectories(dir);
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = cipher(attemptId, Cipher.ENCRYPT_MODE, iv);
            byte[] sealed = cipher.doFinal(JSON.writeValueAsBytes(responses));
            // Write-then-rename so a crash mid-write cannot leave a torn file.
            Path tmp = file(attemptId).resolveSibling("attempt-" + attemptId + ".tmp");
            Files.write(tmp, ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
            Files.move(tmp, file(attemptId), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // The cache is a safety net; failing to write it must not break answering.
            System.err.println("Recovery cache write failed: " + e);
        }
    }

    /** Responses keyed by question id; empty if there is no cache or it is unreadable. */
    public Map<Long, Response> load(long attemptId) {
        Map<Long, Response> out = new LinkedHashMap<>();
        Path f = file(attemptId);
        if (!Files.exists(f)) {
            return out;
        }
        try {
            byte[] all = Files.readAllBytes(f);
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
            byte[] plain = cipher(attemptId, Cipher.DECRYPT_MODE, iv)
                .doFinal(all, 12, all.length - 12);
            List<Response> list = JSON.readValue(plain, new TypeReference<>() {});
            list.forEach(r -> out.put(r.getQuestionId(), r));
        } catch (Exception e) {
            System.err.println("Recovery cache unreadable, ignoring: " + e);
        }
        return out;
    }

    public void delete(long attemptId) {
        try {
            Files.deleteIfExists(file(attemptId));
        } catch (IOException e) {
            System.err.println("Could not delete recovery cache: " + e);
        }
    }

    private Path file(long attemptId) {
        return dir.resolve("attempt-" + attemptId + ".bin");
    }

    private static Cipher cipher(long attemptId, int mode, byte[] iv) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
            .digest(("exampro-recovery-v1:" + attemptId).getBytes(StandardCharsets.UTF_8));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c;
    }

}
