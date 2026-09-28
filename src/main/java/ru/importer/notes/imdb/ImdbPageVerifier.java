package ru.importer.notes.imdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.kp.KpYearValidator;

/**
 * Верификация страницы IMDB: гейт типа по JSON-LD {@code @type}, сверка названия
 * ({@code <title>}, JSON-LD {@code name}/{@code alternateName}) и года, формат imdbId.
 */
@Slf4j
class ImdbPageVerifier {

    /**
     * Допустимые типы тайтла IMDB по JSON-LD ({@code @type}): Movie или TVSeries
     * (сериалы из выгрузки пользователя идут через TVSeries). Всё прочее
     * (PodcastEpisode, TVEpisode, VideoGame и т.д.) — не наш фильм, нужен фолбэк на поиск.
     */
    private static final Set<String> ALLOWED_JSON_LD_TYPES = Set.of("Movie", "TVSeries");
    /**
     * Парсер JSON-LD страниц IMDB.
     */
    private static final ObjectMapper JSON_LD_MAPPER = new ObjectMapper();

    /**
     * Разбирает один JSON-LD-скрипт: собирает @type в allowed/others и (для узлов типа
     * Movie/TVSeries) названия name/alternateName.
     */
    private static void collectJsonLd(String json, Set<String> allowed, List<String> others,
                                      List<String> names) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            collectJsonLdFromNode(JSON_LD_MAPPER.readTree(json), allowed, others, names, 0);
        } catch (Exception ignored) {
            // Битый/нестандартный JSON одного скрипта не должен ломать остальные
        }
    }

    /**
     * Рекурсивный сбор @type и названий: объекты, массивы, массив-тип, {@code @graph}
     * (с защитой глубины рекурсии). name/alternateName берутся только у узлов типа
     * Movie/TVSeries — чтобы не подмешивать названия из BreadcrumbList и прочих посторонних узлов.
     */
    private static void collectJsonLdFromNode(JsonNode node, Set<String> allowed, List<String> others,
                                              List<String> names, int depth) {
        if (depth > 5 || node == null || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectJsonLdFromNode(child, allowed, others, names, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode type = node.get("@type");
        boolean titleNode = false;
        if (type != null) {
            List<String> types = new ArrayList<>();
            if (type.isTextual()) {
                types.add(type.asText());
            } else if (type.isArray()) {
                for (JsonNode t : type) {
                    if (t.isTextual()) {
                        types.add(t.asText());
                    }
                }
            }
            for (String t : types) {
                if (ALLOWED_JSON_LD_TYPES.contains(t)) {
                    allowed.add(t);
                    titleNode = true;
                } else {
                    others.add(t);
                }
            }
        }
        if (titleNode) {
            addJsonLdName(names, node.get("name"));
            addJsonLdName(names, node.get("alternateName"));
        }
        JsonNode graph = node.get("@graph");
        if (graph != null) {
            collectJsonLdFromNode(graph, allowed, others, names, depth + 1);
        }
    }

    /** Добавляет текстовое (или массив текстовых) значение name/alternateName в список. */
    private static void addJsonLdName(List<String> names, JsonNode value) {
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (text != null && !text.isBlank()) {
                names.add(text);
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                addJsonLdName(names, item);
            }
        }
    }

    /**
     * Название из {@code <title>} страницы фильма: срезает хвост « - IMDb»
     * и последнюю скобочную группу; скобки внутри названия сохраняются.
     */
    private static String extractImdbPageName(String pageTitle) {
        return pageTitle
                .replaceFirst("(?i)\\s*-\\s*IMDb\\s*$", "")
                .trim()
                .replaceFirst("\\s*\\([^)]*\\)\\s*$", "")
                .trim();
    }

    /**
     * Год из последней группы в скобках {@code <title>} — именно последней: первый год
     * может быть частью названия («2001: A Space Odyssey (1968)»). Скобок/года нет — 0.
     */
    private static int extractImdbPageYear(String pageTitle) {
        String s = pageTitle.replaceFirst("(?i)\\s*-\\s*IMDb\\s*$", "").trim();
        int open = s.lastIndexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return 0;
        }
        String inner = s.substring(open + 1, close).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(inner);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Данные JSON-LD страницы IMDB: тип тайтла ({@code @type}) и названия из узлов
     * Movie/TVSeries ({@code name}, {@code alternateName}).
     */
    record JsonLdData(String type, List<String> names) {

        static JsonLdData empty() {
            return new JsonLdData(null, List.of());
        }
    }

    /**
     * Тип тайтла и названия (name/alternateName) из JSON-LD страницы IMDB. Скриптов может
     * быть несколько, @type — строка/массив или в {@code @graph}: приоритет у допустимого
     * (Movie/TVSeries), иначе первый посторонний. Все данные собираются одним парсингом.
     */
    static JsonLdData extractJsonLdData(String pageSource) {
        if (pageSource == null || pageSource.isBlank()) {
            return JsonLdData.empty();
        }
        try {
            Document doc = Jsoup.parse(pageSource);
            Set<String> allowed = new HashSet<>();
            List<String> others = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (org.jsoup.nodes.Element script : doc.getElementsByTag("script")) {
                if (!"application/ld+json".equals(script.attr("type"))) {
                    continue;
                }
                collectJsonLd(script.data(), allowed, others, names);
            }
            String type = !allowed.isEmpty() ? allowed.iterator().next()
                    : (others.isEmpty() ? null : others.getFirst());
            return new JsonLdData(type, names);
        } catch (Exception e) {
            log.warn("Не удалось разобрать JSON-LD страницы IMDB (проверка типа пропущена): {}", e.getMessage());
            return JsonLdData.empty();
        }
    }

    /**
     * Тип тайтла ({@code @type}) из JSON-LD страницы IMDB (обратная совместимость).
     */
    static String extractJsonLdType(String pageSource) {
        return extractJsonLdData(pageSource).type();
    }

    /**
     * Валидный формат imdbId: «tt» + цифры (иначе страница по такому id не откроется).
     */
    static boolean isValidImdbIdFormat(String imdbId) {
        return imdbId != null && imdbId.matches("tt\\d+");
    }

    /**
     * Исходник текущей страницы или null (проверка JSON-LD тогда пропускается).
     */
    private static String readPageSource(WebDriver driver) {
        try {
            return driver.getPageSource();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Верификация прямого захода: соответствует ли открытая страница нашему фильму.
     * Проверки по порядку: тип тайтла по JSON-LD (@type вне Movie/TVSeries — отказ,
     * даже если название в {@code <title>} совпало: у эпизода оно может совпадать
     * буквально), название хотя бы из одного источника ({@code <title>}, JSON-LD
     * {@code name}, JSON-LD {@code alternateName}) после нормализации, год (расхождение
     * ≤1 допустимо: год КП может легитимно отличаться от IMDB).
     */
    boolean pageMatchesMovie(WebDriver driver, MovieData movie) {
        String pageTitle;
        try {
            pageTitle = driver.getTitle();
        } catch (Exception e) {
            log.warn("Не удалось прочитать <title> страницы IMDB: {}", e.getMessage());
            pageTitle = null;
        }
        // JSON-LD парсим один раз: и тип, и названия.
        JsonLdData jsonLd = extractJsonLdData(readPageSource(driver));
        // Тип тайтла отсекается до сверки названия: у эпизода название может совпасть
        // буквально. null (JSON-LD нет/не распарсился) — решают проверки названия ниже.
        if (jsonLd.type() != null && !ALLOWED_JSON_LD_TYPES.contains(jsonLd.type())) {
            log.info("Тип тайтла IMDB по JSON-LD: {} (ожидались Movie/TVSeries) — imdbId ведёт "
                             + "не на фильм, сбрасываю и ищу по названию", jsonLd.type());
            return false;
        }
        // Источники названия страницы: <title>, JSON-LD name, JSON-LD alternateName.
        List<String> pageNames = new ArrayList<>();
        if (pageTitle != null && !pageTitle.isBlank()) {
            String pageName = extractImdbPageName(pageTitle);
            if (!pageName.isBlank()) {
                pageNames.add(pageName);
            }
        }
        pageNames.addAll(jsonLd.names());
        if (!nameMatchesAny(pageNames, movie)) {
            return false;
        }
        int pageYear = pageTitle != null ? extractImdbPageYear(pageTitle) : 0;
        if (pageYear > 0 && KpYearValidator.isValidYear(movie.getYear())
                && Math.abs(movie.getYear() - pageYear) > 1) {
            log.info("Год на странице IMDB ({}) отличается от года фильма ({}) больше чем на 1 — это другой фильм",
                     pageYear, movie.getYear());
            return false;
        }
        return true;
    }

    /**
     * Совпало ли ЛЮБОЕ из названий страницы (после нормализации) с ЛЮБЫМ из названий фильма
     * {@link ImdbNotesExporter#candidateTitles}.
     */
    private static boolean nameMatchesAny(List<String> pageNames, MovieData movie) {
        List<String> candidates = ImdbNotesExporter.candidateTitles(movie);
        for (String pageName : pageNames) {
            String normalizedPage = ImdbNotesExporter.normalizeTitle(pageName);
            if (normalizedPage.isEmpty()) {
                continue;
            }
            for (String candidate : candidates) {
                if (ImdbNotesExporter.normalizeTitle(candidate).equals(normalizedPage)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Верификация страницы, открытой из выдачи поиска: точный матч по названию может
     * оказаться эпизодом подкаста/сериала или игрой. @type по JSON-LD вне
     * {Movie, TVSeries} — imdb_id сбрасывается, NOT_FOUND, оценка не ставится.
     * JSON-LD отсутствует/не распарсился — прежнее поведение (название уже сверено).
     * Использует общий одноразовый парсер JSON-LD (заодно собирает name/alternateName):
     * отдельный type-only проход дал бы второй обход Jsoup ради незначительной экономии.
     */
    void verifyPageTypeAfterSearch(WebDriver driver, MovieData movie) {
        String jsonLdType = extractJsonLdType(readPageSource(driver));
        if (jsonLdType != null && !ALLOWED_JSON_LD_TYPES.contains(jsonLdType)) {
            log.info("Открытая из выдачи поиска страница {} — {} (ожидались Movie/TVSeries): "
                             + "точный матч оказался не фильмом, imdb_id сброшен, оценка не ставится",
                     movie.getImdbId(), jsonLdType);
            movie.setImdbId(null);
            movie.setStatus(MovieStatus.NOT_FOUND);
        }
    }

}
