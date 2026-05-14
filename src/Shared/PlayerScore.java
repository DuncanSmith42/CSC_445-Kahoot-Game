package Shared;

/**
 * PlayerScore.java
 *
 * Tracks a single player's name and score.
 * Sent to all clients inside a SCORE or GAME_OVER message
 * as a JSON array so everyone sees the same scoreboard.
 */
public class PlayerScore {

    private String nickname;
    private int score;
    private boolean answered; // did this player answer the current question?

    public PlayerScore(String nickname) {
        this.nickname = nickname;
        this.score = 0;
        this.answered = false;
    }

    // Required by Gson
    public PlayerScore() {}

    // ─── Getters ───────────────────────────────────────────
    public String getNickname()  { return nickname; }
    public int    getScore()     { return score; }
    public boolean hasAnswered() { return answered; }

    // ─── Score management ──────────────────────────────────

    /**
     * Adds points to this player's score.
     * Called by GameServer when a correct answer comes in.
     */
    public void addScore(int points) {
        this.score += points;
    }

    /**
     * Marks this player as having answered the current question.
     * Resets at the start of each new question.
     */
    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    /**
     * Resets answered flag between questions.
     * Called by GameServer before each new question.
     */
    public void resetAnswered() {
        this.answered = false;
    }

    @Override
    public String toString() {
        return String.format("PlayerScore{nickname='%s', score=%d}", nickname, score);
    }
}