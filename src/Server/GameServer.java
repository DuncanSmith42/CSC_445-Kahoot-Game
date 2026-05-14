package Server;

import Shared.GameConfig;
import Shared.Message;
import Shared.PlayerScore;
import Shared.Question;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static java.lang.Thread.sleep;

/**
 * GameServer.java
 *
 * Main server class. Responsibilities:
 *   - Accept exactly 3 player connections
 *   - Coordinate the game loop (questions, answers, scores)
 *   - Track which players have answered each question
 *   - Calculate and broadcast scores
 *   - Enforce security via PlayerHandler
 *
 * Raft consensus will be plugged in here by your teammate —
 * game state changes (answers, scores) will go through the Raft log.
 */
public class GameServer {

    private final Gson gson = new Gson();

    // Connected players
    private final List<PlayerHandler> players = new ArrayList<>();

    // Tracks taken nicknames — ConcurrentHashMap is thread safe
    private final ConcurrentHashMap<String, Boolean> takenNicknames = new ConcurrentHashMap<>();

    // Tracks answers for the current question: nickname → answer
    private final ConcurrentHashMap<String, String> currentAnswers = new ConcurrentHashMap<>();

    // Tracks when each question was sent (for score calculation)
    private long questionSentTime;

    // Current question being asked
    private Question currentQuestion;

    // Latch that waits until all 3 players are connected and ready
    private CountDownLatch readyLatch = new CountDownLatch(GameConfig.MAX_PLAYERS);

    // Whether the game is currently accepting answers
    private volatile boolean acceptingAnswers = false;

    // ─── Entry point ───────────────────────────────────────

    public static void main(String[] args) throws InterruptedException {
        new GameServer().start();
    }
    // ─── Game loop ─────────────────────────────────────────

    /**
     * Main game loop. Runs through every question in order,
     * collects answers, calculates scores, and broadcasts results.
     */
    private void runGame(List<Question> questions) throws InterruptedException {
        System.out.println("[Server] All players ready. Starting game!");

        // Tell all clients the game is starting
        broadcast(new Message(Message.START, "", "SERVER"));

        // Small delay so clients can process the START message
        sleep(2000);

        // Go through every question
        for (int i = 0; i < questions.size(); i++) {
            currentQuestion = questions.get(i);
            currentAnswers.clear();
            acceptingAnswers = true;

            System.out.println("[Server] Question " + (i + 1) + "/" + questions.size());

            // Reset each player's answered flag
            for (PlayerHandler p : players) {
                p.getScore().resetAnswered();
            }

            // Send question to all players and record the time
            questionSentTime = System.currentTimeMillis();
            broadcast(new Message(Message.QUESTION, gson.toJson(currentQuestion), "SERVER"));

            // Run the countdown timer
            runTimer();

            // Time is up — stop accepting answers
            acceptingAnswers = false;

            // Process any answers that came in
            processAnswers();

            // Broadcast current scores
            broadcastScores(Message.SCORE);

            // Show scoreboard for a few seconds before next question
            sleep(GameConfig.SCOREBOARD_DISPLAY_SECONDS * 1000L);
        }

        // Game over — send final scores
        System.out.println("[Server] Game over!");
        broadcastScores(Message.GAME_OVER);

        // Disconnect all players cleanly
        for (PlayerHandler p : players) {
            p.disconnect();
        }
    }
    // ─── Server startup ────────────────────────────────────

    public void start() throws InterruptedException {
        System.out.println("[Server] Starting on port " + GameConfig.PORT);
        System.out.println("[Server] Waiting for " + GameConfig.MAX_PLAYERS + " players...");

        // Load questions
        List<Question> questions = QuestionLoader.loadQuestions(GameConfig.QUESTIONS_FILE);
        if (questions.isEmpty()) {
            System.err.println("[Server] No questions loaded. Exiting.");
            return;
        }

        // Accept player connections
        try (ServerSocket serverSocket = new ServerSocket(GameConfig.PORT)) {
            while (players.size() < GameConfig.MAX_PLAYERS) {
                Socket socket = serverSocket.accept();
                System.out.println("[Server] Connection from: " + socket.getInetAddress());

                // Each player runs on their own thread
                PlayerHandler handler = new PlayerHandler(socket, this);
                players.add(handler);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Failed to start: " + e.getMessage());
            return;
        }

        // Wait for all players to send their nickname and get their token
        try {
            readyLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // All 3 players ready — start the game
        runGame(questions);
    }


    // ─── Timer ─────────────────────────────────────────────

    /**
     * Counts down the question timer, sending a TIMER tick
     * to all clients every second.
     * Also stops early if all players have already answered.
     */
    private void runTimer() throws InterruptedException {
        int seconds = GameConfig.QUESTION_TIMER_SECONDS;

        for (int t = seconds; t >= 0; t--) {
            broadcast(new Message(Message.TIMER, String.valueOf(t), "SERVER"));

            // Stop early if everyone has answered
            if (allPlayersAnswered()) {
                System.out.println("[Server] All players answered early.");
                break;
            }

            sleep(1000);
        }
    }

    // ─── Answer handling ───────────────────────────────────

    /**
     * Called by PlayerHandler when a player submits an answer.
     * Thread safe — multiple players submit on their own threads.
     *
     * @param handler   the player who answered
     * @param answer    the letter they submitted: A, B, C, or D
     * @param arrivedAt server timestamp when the answer arrived
     */
    public synchronized void submitAnswer(PlayerHandler handler, String answer, long arrivedAt) {
        String nickname = handler.getNickname();

        // Only accept answers during the question window
        if (!acceptingAnswers) {
            System.out.println("[Server] Late answer from " + nickname + " — ignored");
            return;
        }

        // Only accept one answer per player per question
        if (handler.getScore().hasAnswered()) {
            System.out.println("[Server] Duplicate answer from " + nickname + " — ignored");
            return;
        }

        // Mark as answered and store their answer with the arrival time
        handler.getScore().setAnswered(true);
        currentAnswers.put(nickname, answer);

        // Calculate elapsed time from when question was sent
        long elapsed = arrivedAt - questionSentTime;

        System.out.println("[Server] Answer from " + nickname
                + ": " + answer + " (" + elapsed + "ms)");

        // Check if correct and award points
        if (currentQuestion.isCorrect(answer)) {
            int points = GameConfig.calculateScore(elapsed,
                    GameConfig.QUESTION_TIMER_SECONDS * 1000L);
            handler.getScore().addScore(points);
            System.out.println("[Server] Correct! Awarded " + points + " points to " + nickname);
        } else {
            System.out.println("[Server] Wrong answer from " + nickname);
        }
    }

    /**
     * After the timer ends, logs a summary of who answered what.
     */
    private void processAnswers() {
        System.out.println("[Server] Correct answer was: " + currentQuestion.getAnswer());
        for (PlayerHandler p : players) {
            String ans = currentAnswers.getOrDefault(p.getNickname(), "no answer");
            System.out.println("  " + p.getNickname() + " → " + ans);
        }
    }

    // ─── Broadcasting ───────────────────────────────────────

    /**
     * Sends a message to every connected player.
     *
     * @param msg the message to broadcast
     */
    public void broadcast(Message msg) {
        for (PlayerHandler p : players) {
            if (p.isConnected()) {
                p.send(msg);
            }
        }
    }

    /**
     * Builds and broadcasts the current scoreboard to all players.
     *
     * @param messageType either Message.SCORE or Message.GAME_OVER
     */
    private void broadcastScores(String messageType) {
        List<PlayerScore> scores = new ArrayList<>();
        for (PlayerHandler p : players) {
            scores.add(p.getScore());
        }

        // Sort by score descending
        scores.sort((a, b) -> b.getScore() - a.getScore());

        String scoresJson = gson.toJson(scores);
        broadcast(new Message(messageType, scoresJson, "SERVER"));
    }

    // ─── Registration ───────────────────────────────────────

    /**
     * Called by PlayerHandler after a player's nickname is accepted.
     * Decrements the latch so the server knows one more player is ready.
     *
     * @param handler the player who is ready
     */
    public void playerReady(PlayerHandler handler) {
        System.out.println("[Server] " + handler.getNickname() + " is ready.");
        readyLatch.countDown(); // was missing — this is what unblocks the latch
    }
    public boolean registerNickname(String nickname) {
        return takenNicknames.putIfAbsent(nickname, true) == null;
    }

    // ─── Helpers ───────────────────────────────────────────

    private boolean allPlayersAnswered() {
        for (PlayerHandler p : players) {
            if (p.isConnected() && !p.getScore().hasAnswered()) {
                return false;
            }
        }
        return true;
    }
}