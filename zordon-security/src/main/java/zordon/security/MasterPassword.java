/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import zordon.api.trace.Spec;

/**
 * A senha mestre do OPPRESSOR MODE, guardada só como derivação.
 *
 * <p>PBKDF2-HMAC-SHA256 com sal próprio por senha, no formato
 * {@code pbkdf2$<iterações>$<sal>$<chave>} com sal e chave em Base64 sem
 * preenchimento. A comparação é em tempo constante: errar o primeiro caractere
 * tem de custar o mesmo que errar o último, senão o tempo de resposta conta
 * quanto do segredo já está certo.
 *
 * <p>O custo de {@value #ITERATIONS} iterações é deliberado: além de encarecer
 * um ataque de dicionário sobre o arquivo, ele é o que limita a força bruta
 * pela porta da frente — cada tentativa errada custa o mesmo trabalho.
 */
@Spec("SPEC-036")
public final class MasterPassword {

    /** Recomendação da OWASP para PBKDF2-HMAC-SHA256. */
    public static final int ITERATIONS = 600_000;

    /**
     * Mínimo de caracteres ao definir a senha.
     *
     * <p>Não é uma etapa de aprovação: vale só na hora de cadastrar, nunca na
     * de entrar. Uma senha curta aqui não protege menos um arquivo — ela abre
     * o processo inteiro.
     */
    public static final int MIN_LENGTH = 12;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private MasterPassword() {}

    /** Deriva a senha para guardar em disco. Não guarda a senha em lugar nenhum. */
    public static String derive(char[] password) {
        Objects.requireNonNull(password, "password");
        if (password.length < MIN_LENGTH) {
            throw new IllegalArgumentException("a senha mestre tem no mínimo " + MIN_LENGTH + " caracteres");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = pbkdf2(password, salt, ITERATIONS);
        try {
            return PREFIX + "$" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Confere a senha contra o que está guardado.
     *
     * <p>Um {@code stored} ausente ou corrompido é "não confere", nunca
     * "confere": sem senha cadastrada não se entra no modo.
     */
    public static boolean matches(char[] password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        byte[] expected;
        byte[] salt;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = DECODER.decode(parts[2]);
            expected = DECODER.decode(parts[3]);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        byte[] actual = pbkdf2(password, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2-HMAC-SHA256 é obrigatório em toda JVM desde a 8; faltar é
            // ambiente quebrado, e aí não há senha que valha conferir.
            throw new IllegalStateException("PBKDF2 indisponível nesta JVM", e);
        } finally {
            spec.clearPassword();
        }
    }
}
