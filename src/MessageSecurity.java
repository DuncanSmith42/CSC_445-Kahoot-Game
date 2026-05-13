
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
public class MessageSecurity {




        // ----------------------------------------------------------------
        // Shared server-to-server secret (all 3 Raft nodes share this)
        // In a real system this would be loaded from a config file or env var
        // ----------------------------------------------------------------
        private static final String INTER_NODE_SECRET = "raft-node-shared-secret-change-me";

        // How old a message can be before we reject it (5 seconds)
        private static final long MAX_MESSAGE_AGE_MS = 5000;

        // Tracks nonces we've seen — prevents replay attacks
        // ConcurrentHashMap is thread-safe (important for a server handling multiple clients)
        private static final Set<String> seenNonces = Collections.newSetFromMap(
                new ConcurrentHashMap<>()
        );

        // ----------------------------------------------------------------
        // HMAC signing
        // ----------------------------------------------------------------

        /**
         * Signs a message by computing its HMAC and storing it in message.hmac.
         * Call this BEFORE sending any message.
         *
         * @param message the message to sign (must have all fields set except hmac)
         * @param secretKey the shared secret between sender and receiver
         */
        public static void sign(Message message, String secretKey) {
            String content = message.getSignableContent();
            String signature = computeHmac(content, secretKey);
            message.setHmac(signature);
        }

        /**
         * Verifies a received message. Checks:
         *   1. HMAC signature matches (integrity)
         *   2. Message is not too old (freshness)
         *   3. Nonce has not been seen before (replay prevention)
         *
         * @param message   the received message
         * @param secretKey the shared secret
         * @return true if valid, false if anything is wrong
         */
        public static boolean verify(Message message, String secretKey) {
            // 1. Basic null checks
            if (message == null || message.getHmac() == null || message.getNonce() == null) {
                System.err.println("[SECURITY] Rejected: missing security fields");
                return false;
            }

            // 2. Check HMAC
            String expected = computeHmac(message.getSignableContent(), secretKey);
            if (!expected.equals(message.getHmac())) {
                System.err.println("[SECURITY] Rejected: HMAC mismatch from " + message.getSenderId());
                return false;
            }

            // 3. Check timestamp — reject messages older than 5 seconds
            long age = System.currentTimeMillis() - message.getTimestamp();
            if (age > MAX_MESSAGE_AGE_MS || age < 0) {
                System.err.println("[SECURITY] Rejected: message too old (" + age + "ms) from " + message.getSenderId());
                return false;
            }

            // 4. Check nonce — reject if we've seen this exact message before
            if (seenNonces.contains(message.getNonce())) {
                System.err.println("[SECURITY] Rejected: duplicate nonce (replay attack?) from " + message.getSenderId());
                return false;
            }
            seenNonces.add(message.getNonce());

            return true;
        }

        // ----------------------------------------------------------------
        // Token generation — for player authentication on join
        // ----------------------------------------------------------------

        /**
         * Generates a secure random token to give a player when they join.
         * The server stores this token and the player must send it with every message.
         *
         * @return a 32-byte Base64-encoded token string
         */
        public static String generatePlayerToken() {
            SecureRandom random = new SecureRandom();
            byte[] tokenBytes = new byte[32];
            random.nextBytes(tokenBytes);
            return Base64.getEncoder().encodeToString(tokenBytes);
        }

        /**
         * Builds the per-player secret key derived from their token.
         * This is what both the server and that specific client use to sign messages.
         * Different from the inter-node secret — each player has their own key.
         *
         * @param playerToken the token issued to the player on join
         * @return a secret key string for that player's session
         */
        public static String playerSessionKey(String playerToken) {
            // Combine the token with a server-side pepper
            // So even if someone guesses the token format, they can't forge signatures
            return playerToken + ":" + INTER_NODE_SECRET;
        }

        // ----------------------------------------------------------------
        // Internal HMAC computation
        // ----------------------------------------------------------------

        private static String computeHmac(String content, String secret) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                SecretKeySpec keySpec = new SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
                );
                mac.init(keySpec);
                byte[] rawHmac = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
                // Convert to hex string
                StringBuilder sb = new StringBuilder();
                for (byte b : rawHmac) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new RuntimeException("HMAC computation failed", e);
            }
        }

        // Prevent instantiation — this is a utility class
        private MessageSecurity() {}
}
