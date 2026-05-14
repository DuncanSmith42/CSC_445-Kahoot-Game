package Server;

import Shared.GameConfig;
import Shared.Message;
import Shared.MessageSecurity;
import Shared.PlayerScore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * PlayerHandler.java
 *
 * Each connected player gets their own PlayerHandler running on its own thread.
 * Responsible for:
 *   - Receiving the player's nickname
 *   - Issuing a session token
 *   - Receiving and validating answers
 *   - Sending messages to this specific player
 */
public class PlayerHandler implements Runnable {

    private final Socket socket;
    private final GameServer server;

    private BufferedReader in;
    private PrintWriter out;

    private String nickname;
    private String sessionKey;   // unique signing key for this player
    private PlayerScore score;

    private volatile boolean connected = true;

    public PlayerHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // Set up input/output streams
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Step 1 — wait for the player's nickname
            if (!receiveNickname()) return;

            // Step 2 — issue a session token back to the player
            issueToken();

            // Step 3 — notify the server this player is ready
            server.playerReady(this);

            // Step 4 — listen for answers until the game ends
            listenForAnswers();

        } catch (IOException e) {
            System.err.println("[PlayerHandler] Connection error for "
                    + (nickname != null ? nickname : "unknown") + ": " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // ─── Setup ─────────────────────────────────────────────

    /**
     * Waits for the client to send their NICKNAME message.
     * Validates it and registers with the server.
     *
     * @return true if nickname was accepted, false if something went wrong
     */
    private boolean receiveNickname() throws IOException {
        String line = in.readLine();
        if (line == null) return false;

        Message msg = Message.fromJson(line);

        // Must be a NICKNAME message
        if (msg == null || !Message.NICKNAME.equals(msg.getType())) {
            sendError("First message must be a NICKNAME.");
            return false;
        }

        String requestedNick = msg.getData();

        // Validate length
        if (requestedNick == null || requestedNick.isBlank()) {
            sendError("Nickname cannot be empty.");
            return false;
        }
        if (requestedNick.length() > GameConfig.MAX_NICKNAME_LENGTH) {
            sendError("Nickname too long. Max " + GameConfig.MAX_NICKNAME_LENGTH + " characters.");
            return false;
        }

        // Check for duplicate nicknames
        if (!server.registerNickname(requestedNick)) {
            sendError("Nickname already taken. Please reconnect with a different name.");
            return false;
        }

        this.nickname = requestedNick;
        this.score    = new PlayerScore(nickname);
        System.out.println("[Server] Player joined: " + nickname);
        return true;
    }

    /**
     * Generates a session token and sends it to the client.
     * From this point on every message is signed with this key.
     */
    private void issueToken() {
        String token = MessageSecurity.generatePlayerToken();
        this.sessionKey = MessageSecurity.playerSessionKey(token);

        // Send the token to the client — they need it to sign future messages
        Message tokenMsg = new Message(Message.AUTH_TOKEN, token, "SERVER");
        MessageSecurity.sign(tokenMsg, sessionKey);
        out.println(tokenMsg.toJson());

        System.out.println("[Server] Issued token to: " + nickname);
    }

    // ─── Answer listening ───────────────────────────────────

    /**
     * Main loop — listens for ANSWER messages from the client.
     * Validates security on every message before processing.
     */
    private void listenForAnswers() throws IOException {
        while (connected) {
            String line = in.readLine();
            if (line == null) break; // client disconnected

            Message msg = Message.fromJson(line);
            if (msg == null) continue;

            // Security check — reject anything that fails verification
            if (!MessageSecurity.verify(msg, sessionKey)) {
                System.err.println("[SECURITY] Invalid message from " + nickname + " — rejected");
                sendError("Message failed security verification.");
                continue;
            }

            // Only process ANSWER messages during the game
            if (Message.ANSWER.equals(msg.getType())) {
                server.submitAnswer(this, msg.getData(), System.currentTimeMillis());
            }
        }
    }

    // ─── Sending ────────────────────────────────────────────

    /**
     * Sends any message to this player, signed with their session key.
     * All outgoing server messages go through here.
     *
     * @param msg the message to send
     */
    public void send(Message msg) {
        if (!connected) return;
        MessageSecurity.sign(msg, sessionKey);
        out.println(msg.toJson());
    }

    /**
     * Sends an ERROR message to this player.
     *
     * @param reason human readable error description
     */
    public void sendError(String reason) {
        // Error messages before token issuance use a fallback key
        String key = (sessionKey != null) ? sessionKey : "error-fallback";
        Message err = new Message(Message.ERROR, reason, "SERVER");
        MessageSecurity.sign(err, key);
        out.println(err.toJson());
    }

    // ─── Cleanup ────────────────────────────────────────────

    /**
     * Cleanly closes this player's connection.
     */
    public void disconnect() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("[PlayerHandler] Error closing socket for " + nickname);
        }
        System.out.println("[Server] Player disconnected: " + nickname);
    }

    // ─── Getters ────────────────────────────────────────────

    public String      getNickname()  { return nickname; }
    public PlayerScore getScore()     { return score; }
    public boolean     isConnected()  { return connected; }
}