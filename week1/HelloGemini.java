import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uruchomienie z głównego folderu projektu:
 *   java week1/HelloGemini.java
 */
public class HelloGemini {

    static final String MODEL = "gemini-3.1-flash-lite";

    public static void main(String[] args) throws Exception {
        String apiKey = loadApiKey();

        // Treść żądania (JSON składany ręcznie, żeby zobaczyć, jak wygląda "pod spodem")
        String body = """
            {
              "contents": [
                { "role": "user",
                  "parts": [ { "text": "Wyjaśnij w 10 zdaniach, czym jest OWU w ubezpieczeniach." } ] }
              ]
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                        + MODEL + ":generateContent"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status: " + response.statusCode());
        System.out.println(response.body());
        // Co zauważyć w odpowiedzi: "text" (odpowiedź) oraz "usageMetadata" (liczba tokenów).
    }

    /** Klucz ze zmiennej środowiskowej albo z pliku .env w bieżącym folderze. */
    static String loadApiKey() throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) return key;

        Path env = Path.of(".env");
        if (Files.exists(env)) {
            for (String line : Files.readAllLines(env)) {
                if (line.startsWith("GEMINI_API_KEY=")) {
                    return line.substring("GEMINI_API_KEY=".length()).trim();
                }
            }
        }
        throw new IllegalStateException("Brak GEMINI_API_KEY (zmienna środowiskowa lub plik .env)");
    }
}
