
import com.google.gson.Gson;
import java.util.UUID;

/**
 * Message.java
 *
 * Every packet sent between server and client is a Message.
 * Serialized to JSON, sent over the socket, deserialized on the other side.
 *
 * Security fields (senderId, timestamp, nonce, hmac) are populated
 * by MessageSecurity.sign() before sending, and validated by
 * MessageSecurity.verify() on receipt.
 */
public class Message {

    // ─── Message type constants ────────────────────────────

    /** Client → Server: player's chosen nickname. data = nickname string. */
    public static final String NICKNAME   = "NICKNAME";

    /** Server → Client: issued after NICKNAME accepted. data = session token string. */
    public static final String AUTH_TOKEN = "AUTH_TOKEN";

    /** Server → Client: a new question. data = JSON of a Question object. */
    public static final String QUESTION   = "QUESTION";

    /** Server → Client: countdown tick. data = seconds remaining, e.g. "14". */
    public static final String TIMER      = "TIMER";

    /** Client → Server: player's answer. data = "A", "B", "C", or "D". */
    public static final String ANSWER     = "ANSWER";

    /** Server → Client: current scoreboard. data = JSON array of PlayerScore objects. */
    public static final String SCORE      = "SCORE";

    /** Server → Client: game has ended. data = final scoreboard JSON. */
    public static final String GAME_OVER  = "GAME_OVER";

    /** Server → Client: something went wrong. data = human-readable error string. */
    public static final String ERROR      = "ERROR";

    /** Server → Client: all players connected, game starting. data = empty string. */
    public static final String START      = "START";

    // ─── Core fields ───────────────────────────────────────

    private String type;
    private String data;

    // ─── Security fields ───────────────────────────────────

    /** Who sent this — player nickname or "SERVER". */
    private String senderId;

    /** Unix time in milliseconds when this message was created. */
    private long timestamp;

    /** Unique ID for this message — used to prevent replay attacks. */
    private String nonce;

    /** HMAC-SHA256 signature — set by MessageSecurity.sign(), verified by MessageSecurity.verify(). */
    private String hmac;

    // ─── Constructors ──────────────────────────────────────

    /**
     * Main constructor. Use this for all new messages.
     * Automatically sets timestamp and nonce.
     * You still need to call MessageSecurity.sign(msg, key) before sending.
     *
     * @param type     one of the constants above (e.g. Message.ANSWER)
     * @param data     the payload string (can be empty, never null)
     * @param senderId who is sending this — player nickname or "SERVER"
     */
    public Message(String type, String data, String senderId) {
        this.type      = type;
        this.data      = data;
        this.senderId  = senderId;
        this.timestamp = System.currentTimeMillis();
        this.nonce     = UUID.randomUUID().toString();
        // hmac intentionally left null until MessageSecurity.sign() is called
    }

    /** Required by Gson for deserialization. Do not use directly. */
    public Message() {}

    // ─── Getters ───────────────────────────────────────────

    public String getType()            { return type; }
    public String getData()            { return data; }
    public String getSenderId()        { return senderId; }
    public long   getTimestamp()       { return timestamp; }
    public String getNonce()           { return nonce; }
    public String getHmac()            { return hmac; }

    // ─── Setter (MessageSecurity use only) ─────────────────

    /** Called only by MessageSecurity.sign(). Do not call this directly. */
    public void setHmac(String hmac)   { this.hmac = hmac; }

    // ─── Security helper ───────────────────────────────────

    /**
     * Builds the string that gets signed by HMAC.
     * Every field that matters for integrity is included.
     * Must produce the exact same string on sender and receiver.
     */
    public String getSignableContent() {
        return type + "|" + data + "|" + senderId + "|" + timestamp + "|" + nonce;
    }

    // ─── Serialization ─────────────────────────────────────

    /** Serializes this message to a JSON string ready to send over the socket. */
    public String toJson() {
        return new Gson().toJson(this);
    }

    /**
     * Deserializes a raw JSON string from the socket back into a Message.
     *
     * @param json a raw line received from the socket
     * @return the parsed Message, or null if malformed
     */
    public static Message fromJson(String json) {
        try {
            return new Gson().fromJson(json, Message.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Debug ─────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format("Message{type='%s', sender='%s', nonce='%s', hmac='%s'}",
                type, senderId, nonce, hmac != null ? hmac.substring(0, 8) + "..." : "UNSIGNED");
    }
}