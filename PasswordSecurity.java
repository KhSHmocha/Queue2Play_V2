import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordSecurity {
    private static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();

    static boolean isHash(String value) {
        return value != null && value.startsWith("pbkdf2$");
    }

    static String hash(String password) {
        if (password == null) {
            return null;
        }
        if (isHash(password)) {
            validateHash(password);
            return password;
        }
        return hashPlaintext(password);
    }

    static String hashPlaintext(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password is required.");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
        + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    static void validateHash(String encoded) {
        String[] p = encoded.split("\\$", -1);
        if (p.length != 4 || !p[0].equals("pbkdf2")) {
            throw new IllegalArgumentException("Invalid password hash.");
        }
        int iterations = Integer.parseInt(p[1]);
        if (iterations < 100000 || iterations > 1000000 || Base64.getDecoder().decode(p[2]).length != 16
            || Base64.getDecoder().decode(p[3]).length != 32) {
            throw new IllegalArgumentException("Invalid password hash.");
        }
    }

    static boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        try {
            validateHash(encoded);
            String[] p = encoded.split("\\$");
            return MessageDigest.isEqual(Base64.getDecoder().decode(p[3]),
                derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing unavailable.", e);
        } finally {
            spec.clearPassword();
        }
    }
}

