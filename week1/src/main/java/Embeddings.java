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
import java.util.List;

/**
 * Embeddingi: zamiana zdań na wektory i porównanie ich podobieństwem kosinusowym.
 *
 * Idea: zdania o podobnym znaczeniu mają wektory skierowane w podobną stronę
 * (cosinus bliski 1), a zdania o czymś innym są dalej od siebie.
 * To jest cała podstawa wyszukiwania w RAG.
 *
 * Zdania poniżej są WYMYŚLONE na potrzeby ćwiczenia (nie z prawdziwych OWU).
 * Podmień je na własne fragmenty z PDF-ów, gdy je zbierzesz.
 *
 * Model można zmienić zmienną środowiskową EMBEDDING_MODEL.
 */
public class Embeddings {

    static final String MODEL = System.getenv().getOrDefault("EMBEDDING_MODEL", "gemini-embedding-001");
    static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":batchEmbedContents";
    static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> ZDANIA = List.of(
            "Towarzystwo odpowiada za szkody powstałe wskutek zalania wodą z instalacji wodociągowej.",   // 0 zalanie
            "Ubezpieczyciel pokrywa szkody spowodowane pęknięciem rury i wyciekiem wody w mieszkaniu.",    // 1 zalanie (inne słowa)
            "Odszkodowanie przysługuje za kradzież z włamaniem do lokalu mieszkalnego.",                   // 2 kradzież
            "Z odszkodowania potrąca się franszyzę redukcyjną w wysokości 200 zł.",                        // 3 franszyza
            "Odszkodowaniu podlegają takie przypadki jak awaria pralki lub grzejników"                                     // 4 pogoda (kontrola)
    );

    public static void main(String[] args) throws Exception {
        String apiKey = loadApiKey();

        List<double[]> wektory = embed(apiKey, ZDANIA);
        System.out.println("Model: " + MODEL + ", liczba wymiarów wektora: " + wektory.get(0).length + "\n");

        // Macierz podobieństw (1,00 = identyczny kierunek)
        System.out.print("      ");
        for (int j = 0; j < ZDANIA.size(); j++) System.out.printf("  [%d] ", j);
        System.out.println();
        for (int i = 0; i < ZDANIA.size(); i++) {
            System.out.printf("[%d]   ", i);
            for (int j = 0; j < ZDANIA.size(); j++) {
                System.out.printf(" %.3f", cosine(wektory.get(i), wektory.get(j)));
            }
            System.out.println();
        }

        // Co jest najbliższe zdaniu 0?
        System.out.println("\nNajbliższe zdaniu [0] (zalanie):");
        for (int j = 1; j < ZDANIA.size(); j++) {
            System.out.printf("  [%d] %.3f  %s%n", j, cosine(wektory.get(0), wektory.get(j)), ZDANIA.get(j));
        }
    }

    /** Podobieństwo kosinusowe: iloczyn skalarny podzielony przez iloczyn długości wektorów. */
    static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Jedno zapytanie do API zwraca wektory dla wszystkich zdań naraz. */
    static List<double[]> embed(String apiKey, List<String> teksty) throws Exception {
        ObjectNode zadanie = JSON.createObjectNode();
        ArrayNode requests = zadanie.putArray("requests");
        for (String tekst : teksty) {
            ObjectNode r = requests.addObject();
            r.put("model", "models/" + MODEL);
            r.putObject("content").putArray("parts").addObject().put("text", tekst);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(zadanie)))
                .build();

        // Serwer bywa przeciążony (503) albo limituje zapytania (429): ponawiamy z rosnącą przerwą.
        HttpResponse<String> response = null;
        for (int proba = 1; proba <= 5; proba++) {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 503 && status != 429) break;
            System.out.println("HTTP " + status + ", próba " + proba + "/5, czekam " + (proba * 4) + " s...");
            Thread.sleep(proba * 4000L);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode embeddings = JSON.readTree(response.body()).path("embeddings");
        List<double[]> wynik = new java.util.ArrayList<>();
        for (JsonNode e : embeddings) {
            JsonNode values = e.path("values");
            double[] v = new double[values.size()];
            for (int i = 0; i < v.length; i++) v[i] = values.get(i).asDouble();
            wynik.add(v);
        }
        if (wynik.size() != teksty.size()) {
            throw new IllegalStateException("Oczekiwano " + teksty.size() + " wektorów, jest " + wynik.size()
                    + ". Odpowiedź: " + response.body());
        }
        return wynik;
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
