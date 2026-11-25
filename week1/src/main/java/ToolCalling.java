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
import java.util.Map;

/**
 * Tool calling bez frameworka.
 *
 * Przepływ:
 *   1. wysyłamy pytanie + deklarację narzędzia "definicja_pojecia"
 *   2. model NIE odpowiada, tylko zwraca functionCall (prosi o wywołanie funkcji)
 *   3. NASZ kod wykonuje metodę Javy (model niczego nie uruchamia)
 *   4. odsyłamy wynik jako functionResponse, model składa końcową odpowiedź
 *
 * Uruchomienie: z IntelliJ (przycisk Run przy main) albo z folderu week1:
 *   mvn -q compile exec:java -Dexec.mainClass=ToolCalling
 * Własne pytanie: podaj je jako argument programu.
 */
public class ToolCalling {

    // Model można zmienić bez edycji kodu: zmienna środowiskowa GEMINI_MODEL.
    static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.6-flash");
    static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    static final ObjectMapper JSON = new ObjectMapper();

    /** Nasze "narzędzie": prosty słowniczek pojęć ubezpieczeniowych. */
    static final Map<String, String> SLOWNIK = Map.of(
            "franszyza redukcyjna",
            "Stała kwota lub procent, o który towarzystwo pomniejsza każde wypłacane odszkodowanie.",
            "suma ubezpieczenia",
            "Maksymalna kwota, do jakiej towarzystwo odpowiada za szkodę w okresie ubezpieczenia.",
            "zalanie",
            "Szkoda wyrządzona przez wodę z instalacji, urządzeń wodnych lub przedostała się z zewnątrz.",
            "udział własny",
            "Procent szkody, który pokrywa ubezpieczony ze swoich środków."
    );

    static String definicjaPojecia(String termin) {
        return SLOWNIK.getOrDefault(termin.toLowerCase().trim(), "Brak definicji w słowniku.");
    }

    public static void main(String[] args) throws Exception {
        String apiKey = loadApiKey();
        String pytanie = args.length > 0
                ? String.join(" ", args)
                : "Co znaczy ukrasc rower?";

        ArrayNode contents = JSON.createArrayNode();
        contents.add(userText(pytanie));

        // Pętla: model może prosić o funkcję kilka razy (np. próbować innego hasła),
        // dopóki nie zwróci zwykłego tekstu. Limit rund chroni przed pętlą bez końca.
        final int MAX_RUND = 5;
        for (int runda = 1; runda <= MAX_RUND; runda++) {
            JsonNode odpowiedz = generate(apiKey, contents);
            JsonNode trescModelu = odpowiedz.at("/candidates/0/content");
            JsonNode wywolanie = znajdzFunctionCall(trescModelu);

            if (wywolanie == null) {
                // Model skończył: wypisujemy tekst (albo diagnostykę, gdy tekstu brak).
                String tekst = wyciagnijTekst(trescModelu);
                System.out.println("\nKońcowa odpowiedź (runda " + runda + "): "
                        + (tekst.isBlank() ? "[PUSTA]" : tekst));
                if (tekst.isBlank()) {
                    System.out.println("finishReason: " + odpowiedz.at("/candidates/0/finishReason").asText("brak"));
                    System.out.println("Surowa odpowiedź: " + odpowiedz);
                }
                JsonNode u = odpowiedz.path("usageMetadata");
                System.out.println("\nTokeny (ostatnie wywołanie): wejście=" + u.path("promptTokenCount").asText()
                        + ", wyjście=" + u.path("candidatesTokenCount").asText()
                        + ", razem=" + u.path("totalTokenCount").asText());
                return;
            }

            // Model prosi o wywołanie funkcji
            String nazwa = wywolanie.path("name").asText();
            String termin = wywolanie.path("args").path("termin").asText();
            System.out.println(runda + ") Model prosi o funkcję: " + nazwa + "(termin=\"" + termin + "\")");

            // Funkcję wykonuje NASZ kod
            String wynik = definicjaPojecia(termin);
            System.out.println("   Nasz kod zwrócił:      " + wynik);

            // Odsyłamy wynik. Wiadomość modelu wraca BEZ ZMIAN.
            contents.add(trescModelu);
            contents.add(functionResponse(nazwa, wynik));
        }
        System.out.println("Przerwano po " + MAX_RUND + " rundach: model wciąż prosi o funkcje.");
    }

    // ---------- Budowanie żądań ----------

    static JsonNode generate(String apiKey, ArrayNode contents) throws Exception {
        ObjectNode zadanie = JSON.createObjectNode();
        zadanie.set("contents", contents);
        zadanie.set("tools", JSON.readTree("""
                [ { "functionDeclarations": [ {
                      "name": "definicja_pojecia",
                      "description": "Zwraca definicję pojęcia ubezpieczeniowego ze słowniczka OWU.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "termin": { "type": "string",
                                      "description": "Pojęcie do zdefiniowania, np. franszyza redukcyjna" }
                        },
                        "required": ["termin"]
                      }
                } ] } ]
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

    static ObjectNode userText(String tekst) {
        ObjectNode wiadomosc = JSON.createObjectNode();
        wiadomosc.put("role", "user");
        wiadomosc.putArray("parts").addObject().put("text", tekst);
        return wiadomosc;
    }

    static ObjectNode functionResponse(String nazwa, String wynik) {
        ObjectNode wiadomosc = JSON.createObjectNode();
        wiadomosc.put("role", "user");
        ObjectNode fr = wiadomosc.putArray("parts").addObject().putObject("functionResponse");
        fr.put("name", nazwa);
        fr.putObject("response").put("result", wynik);
        return wiadomosc;
    }

    // ---------- Czytanie odpowiedzi ----------

    static JsonNode znajdzFunctionCall(JsonNode trescModelu) {
        for (JsonNode part : trescModelu.path("parts")) {
            if (part.has("functionCall")) return part.get("functionCall");
        }
        return null;
    }

    static String wyciagnijTekst(JsonNode trescModelu) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : trescModelu.path("parts")) {
            if (part.has("text")) sb.append(part.get("text").asText());
        }
        return sb.toString();
    }

    // ---------- Klucz API ----------

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
