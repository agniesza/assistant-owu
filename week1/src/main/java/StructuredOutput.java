import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Structured output: odpowiedź modelu jako JSON zgodny ze schematem,
 * zamieniona na rekord Javy.
 *
 * Różnica względem zwykłego tekstu: w generationConfig podajemy
 *   responseMimeType = application/json
 *   responseJsonSchema = schemat (standardowy JSON Schema)
 * i dostajemy JSON, który można bezpiecznie sparsować.
 *
 * Własne pytanie: podaj je jako argument programu.
 */
public class StructuredOutput {

    // Model można zmienić bez edycji kodu: zmienna środowiskowa GEMINI_MODEL.
    static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.6-flash");
    static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    static final ObjectMapper JSON = new ObjectMapper();

    /** Docelowy kształt odpowiedzi asystenta. */
    record OdpowiedzOwu(String odpowiedz, String cytat, double pewnosc) {}

    /** WYMYŚLONY fragment na potrzeby ćwiczenia, nie prawdziwe OWU. Podmień na swój z PDF-a. */
    static final String FRAGMENT = """
            § 7. Zalanie
            1. Towarzystwo odpowiada za szkody powstałe wskutek zalania, to jest działania wody
               pochodzącej z wodociągowych instalacji, urządzeń wodnych lub urządzeń grzewczych.
            2. Towarzystwo nie odpowiada za szkody powstałe wskutek zalania w wyniku zaniedbania
               obowiązku konserwacji instalacji, a także za szkody w mieniu przechowywanym
               w piwnicy na wysokości mniejszej niż 10 cm od podłogi.
            3. W razie szkody stosuje się franszyzę redukcyjną w wysokości 200 zł.
            """;

    public static void main(String[] args) throws Exception {
        String apiKey = loadApiKey();
        String pytanie = args.length > 0
                ? String.join(" ", args)
                : "Czy polisa obejmuje kradzież roweru?";

        String prompt = """
                Odpowiedz na pytanie WYŁĄCZNIE na podstawie fragmentu OWU poniżej.
                - "odpowiedz": krótka odpowiedź po polsku,
                - "cytat": dosłowny fragment OWU, który uzasadnia odpowiedź (bez zmian w tekście),
                - "pewnosc": liczba od 0 do 1.
                Jeśli fragment nie zawiera odpowiedzi, napisz w "odpowiedz": "brak informacji w fragmencie",
                a "pewnosc" ustaw na 0.

                FRAGMENT OWU:
                %s

                PYTANIE: %s
                """.formatted(FRAGMENT, pytanie);

        JsonNode wynik = generate(apiKey, prompt);
        String tekstJson = wyciagnijTekst(wynik.at("/candidates/0/content"));

        System.out.println("Surowy JSON od modelu:\n" + tekstJson + "\n");

        // JSON -> rekord Javy
        OdpowiedzOwu odp = JSON.readValue(tekstJson, OdpowiedzOwu.class);
        System.out.println("Odpowiedź: " + odp.odpowiedz());
        System.out.println("Cytat:     " + odp.cytat());
        System.out.println("Pewność:   " + odp.pewnosc());

        // Prosta kontrola jakości: czy cytat naprawdę występuje we fragmencie?
        boolean cytatPoprawny = odp.cytat() != null
                && FRAGMENT.replaceAll("\\s+", " ").contains(odp.cytat().replaceAll("\\s+", " ").trim());
        System.out.println("Cytat występuje we fragmencie: " + cytatPoprawny);

        JsonNode u = wynik.path("usageMetadata");
        System.out.println("\nTokeny: wejście=" + u.path("promptTokenCount").asText()
                + ", wyjście=" + u.path("candidatesTokenCount").asText());
    }

    static JsonNode generate(String apiKey, String prompt) throws Exception {
        ObjectNode zadanie = JSON.createObjectNode();

        ObjectNode wiadomosc = zadanie.putArray("contents").addObject();
        wiadomosc.put("role", "user");
        wiadomosc.putArray("parts").addObject().put("text", prompt);

        ObjectNode config = zadanie.putObject("generationConfig");
        config.put("temperature", 0);
        config.put("responseMimeType", "application/json");
        config.set("responseJsonSchema", JSON.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "odpowiedz": { "type": "string" },
                    "cytat":     { "type": "string" },
                    "pewnosc":   { "type": "number" }
                  },
                  "required": ["odpowiedz", "cytat", "pewnosc"]
                }
                """));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(zadanie)))
                .build();

        // Serwer bywa przeciążony (503) albo limituje zapytania (429): ponawiamy z rosnącą przerwą.
        HttpResponse<String> response = null;
        for (int proba = 1; proba <= 5; proba++) {
            response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 503 && status != 429) break;
            System.out.println("HTTP " + status + ", próba " + proba + "/5, czekam " + (proba * 4) + " s...");
            Thread.sleep(proba * 4000L);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    static String wyciagnijTekst(JsonNode trescModelu) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : trescModelu.path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                sb.append(part.get("text").asText());
            }
        }
        return sb.toString();
    }

    static String loadApiKey() throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key != null && !key.isBlank()) return key;

        for (String sciezka : new String[]{".env", "../.env"}) {
            Path env = Path.of(sciezka);
            if (Files.exists(env)) {
                for (String line : Files.readAllLines(env)) {
                    if (line.startsWith("GEMINI_API_KEY=")) {
                        return line.substring("GEMINI_API_KEY=".length()).trim();
                    }
                }
            }
        }
        throw new IllegalStateException("Brak GEMINI_API_KEY (zmienna środowiskowa lub plik .env)");
    }
}
