package Server;

import Shared.Question;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QuestionLoader.java
 *
 * Reads questions.json from disk and returns a shuffled list of Question objects.
 * Used by GameServer at the start of each game.
 */
public class QuestionLoader {

    /**
     * Loads all questions from the JSON file defined in GameConfig.
     * Shuffles them so each game has a different order.
     *
     * @param filePath path to the questions.json file
     * @return shuffled list of Question objects, or empty list if file fails to load
     */
    public static List<Question> loadQuestions(String filePath) {
        try {
            Gson gson = new Gson();
            FileReader reader = new FileReader(filePath);

            // Tell Gson we want a List<Question>, not just a plain List
            Type questionListType = new TypeToken<List<Question>>() {}.getType();
            List<Question> questions = gson.fromJson(reader, questionListType);

            reader.close();

            if (questions == null || questions.isEmpty()) {
                System.err.println("[QuestionLoader] No questions found in: " + filePath);
                return new ArrayList<>();
            }

            // Shuffle so every game is different
            Collections.shuffle(questions);

            System.out.println("[QuestionLoader] Loaded " + questions.size() + " questions from " + filePath);
            return questions;

        } catch (IOException e) {
            System.err.println("[QuestionLoader] Failed to read file: " + filePath);
            System.err.println("[QuestionLoader] Error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // Prevent instantiation — this is a utility class
    private QuestionLoader() {}

}