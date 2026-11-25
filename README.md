# Asystent OWU

Asystent AI odpowiadający na pytania o ogólne warunki ubezpieczenia (OWU), np.
„czy polisa obejmuje zalanie mieszkania?”. Odpowiada wyłącznie na podstawie publicznych
dokumentów i zawsze podaje fragment źródłowy.

**Status:** projekt edukacyjny w budowie (etap 1 planu nauki: AI Engineer na bazie Javy).

**Stack (docelowo):** Java, Spring AI, RAG (pgvector), serwer MCP, ewaluacja jakości odpowiedzi,
wdrożenie w chmurze.

## Zasady projektu

- Korzystam wyłącznie z dokumentów publicznie dostępnych w internecie (OWU towarzystw
  ubezpieczeniowych ze stron internetowych). Nie używam kodu, danych ani dokumentów wewnętrznych
  żadnego pracodawcy.
- PDF-y z OWU nie są częścią repozytorium (folder `docs/owu/` jest w `.gitignore`).
- Projekt jest ćwiczeniem technicznym. Odpowiedzi asystenta nie są poradą ubezpieczeniową
  ani prawną.

## Struktura repozytorium

| Folder / plik | Zawartość |
|---|---|
| `week1/` | Ćwiczenia z czystej Javy (bez frameworka): wywołanie API, tool calling, structured output, embeddingi |
| `docs/owu/` | Publiczne OWU w PDF (tylko lokalnie) |
| `.env.example` | Wzór pliku z kluczem API (prawdziwy `.env` jest ignorowany przez Git) |

## Uruchomienie ćwiczeń (`week1/`)

1. JDK 25 (Temurin) i IntelliJ IDEA.
2. Skopiuj `.env.example` jako `.env` w głównym folderze i wpisz swój klucz Google Gemini
   (`GEMINI_API_KEY=...`). Darmowy klucz z Google AI Studio wystarcza.
3. Otwórz folder `week1` w IntelliJ (projekt Maven) i uruchom wybraną klasę.
4. Model można zmienić zmienną środowiskową `GEMINI_MODEL` (dla embeddingów: `EMBEDDING_MODEL`).

| Klasa | Co pokazuje |
|---|---|
| `HelloGemini` | Pojedyncze zapytanie przez `HttpClient`, czytanie odpowiedzi i liczby tokenów |
| `ToolCalling` | Pętla tool callingu: model prosi o funkcję, kod ją wykonuje, wynik wraca do modelu |
| `StructuredOutput` | Odpowiedź jako JSON ze schematem (odpowiedź, cytat, pewność) zamieniona na rekord Javy |
| `Embeddings` | Wektory zdań i podobieństwo kosinusowe |

## Plan

- [x] Tygodnie 1–2: fundamenty LLM w czystej Javie
- [ ] Tygodnie 3–6: Spring AI i LangChain4j, Asystent OWU v0.1
- [ ] Tygodnie 8–11: RAG, chunking, pgvector, cytowanie źródeł (v0.2)
- [ ] Tygodnie 12–15: agenci i serwer MCP (v0.3)
- [ ] Tygodnie 16–19: ewaluacja i observability (v0.4)
- [ ] Tygodnie 20–24: wdrożenie w chmurze (v1.0)

## Dziennik nauki

### Tydzień 1 (16–25.11.2026)

- **16.11** – Przygotowanie środowiska: Java, Docker, Ollama (lokalny model), klucz API
  Gemini z darmowego tieru, to repozytorium.
- **Do 21.11** – Tokeny, okno kontekstu i koszty: porównanie kosztu 1000 zapytań dla dwóch
  modeli według cennika dostawcy. Kurs „ChatGPT Prompt Engineering for Developers”.
- **22.11** – Pierwsze wywołanie API z czystej Javy (`HttpClient`) i tool calling.
  Funkcję wykonuje nasz kod, a nie model. Model tylko prosi o jej wywołanie.
- **25.11** – Obserwacje z tool callingu i structured output:
  - Gdy narzędzie nie zna odpowiedzi, model może wołać je wielokrotnie, a na końcu odpowiedzieć
    z własnej wiedzy, bez źródła. Odpowiedź brzmi pewnie, ale nie da się jej zweryfikować.
    To główny powód, dla którego dalej będzie RAG z obowiązkowym cytatem.
  - Tokeny „myślenia” modelu są rozliczane jak wyjście. W jednym z uruchomień: wejście 518,
    wyjście 525, razem 1522 tokeny, czyli ok. 480 tokenów więcej niż widoczna odpowiedź.
    Rzeczywisty koszt jest więc wyższy niż wynika z długości tekstu.
  - Structured output z wymaganym cytatem i prosta kontrola, czy cytat naprawdę występuje we
    fragmencie, to zalążek przyszłych testów jakości.
  - Serwer bywa przeciążony (HTTP 503) lub limituje zapytania (429), więc klient ponawia
    zapytania z rosnącą przerwą.
- **25.11** – Embeddingi: zdania o podobnym znaczeniu mają bliskie wektory, nawet gdy prawie
  nie mają wspólnych słów. To podstawa wyszukiwania w RAG.
- **25.11** – Zebranie 7 publicznych OWU ubezpieczeń mieszkaniowych od różnych towarzystw.
  Część dokumentów jest starsza (2010, 2017), więc rok wydania zapiszę jako metadaną.

**Następny krok:** tydzień 3, Spring AI (`ChatClient`, szablony promptów, structured output
do rekordów Javy).
