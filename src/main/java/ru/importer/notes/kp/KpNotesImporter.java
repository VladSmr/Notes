package ru.importer.notes.kp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.movie.ImportProgress;

/**
 * Selenium-фазы импорта оценок КП: сканирование страниц оценок ({@link #getNotes}),
 * дозагрузка оригинальных названий и валидация годов со страниц фильмов.
 * Разбор HTML-документа выгрузки — в {@link KpRatingsPageParser}.
 */
@Service
@Slf4j
public class KpNotesImporter {

    static final int PER_PAGE = 20;
    private static final int MAX_PAGES = 300;
    private static final String VOTES_URL_PAGE = "https://www.kinopoisk.ru/user/%d/movies/voted-watched/?page=%d";

    private final KpRatingsPageParser parser = new KpRatingsPageParser();

    /**
     * Год выпуска со страницы фильма КП по убыванию надёжности: элемент с годом →
     * og:title → &lt;title&gt; → первый год из текста страницы (крайний случай).
     *
     * @return год или 0, если определить не удалось
     */
    private int extractYearFromCurrentPage(WebDriver driver) {
        try {
            Document doc = Jsoup.parse(driver.getPageSource());

            Element yearEl = doc.selectFirst("span[class*=\"year\"], [class*=\"Year\"], "
                                                     + ".film-date, [class*=\"film-date\"], [class*=\"filmDate\"]");
            if (yearEl != null) {
                int y = parser.parseYearDigits(yearEl.text());
                if (y > 0) {
                    return y;
                }
            }

            Element ogTitle = doc.selectFirst("meta[property=\"og:title\"]");
            if (ogTitle != null) {
                int y = parser.extractYear(ogTitle.attr("content"));
                if (y > 0) {
                    return y;
                }
            }

            Element title = doc.selectFirst("title");
            if (title != null) {
                int y = parser.extractYear(title.text());
                if (y > 0) {
                    return y;
                }
            }

            // Крайний fallback (риск: год каста/другого фильма) — только как последняя
            // стратегия; единый диапазон 1890–2050 отсекает явный мусор.
            Matcher m = KpRatingsPageParser.PLAIN_YEAR.matcher(doc.text());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception e) {
            log.warn("Не удалось извлечь год со страницы фильма: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Доставляет оригинальные названия для фильмов, у которых их нет, со страниц
     * фильмов КП; попутно заполняет год=0. Промежуточный дамп — через {@code onBatch}
     * каждые 5 обработанных фильмов и в конце фазы (в т.ч. при остановке пользователем).
     */
    public void fetchOriginalTitles(List<MovieData> movies, WebDriver driver, ImportProgress progress,
                                    java.util.function.Consumer<List<MovieData>> onBatch) {
        List<MovieData> missing = new ArrayList<>();
        for (MovieData m : movies) {
            if (m.getNameEn() == null || m.getNameEn().isBlank()) {
                missing.add(m);
            }
        }
        if (missing.isEmpty()) {
            log.info("Оригинальные названия есть у всех фильмов, проход не нужен");
            return;
        }
        log.info("Загружаю оригинальные названия: {} фильмов", missing.size());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));

        int done = 0;
        for (MovieData movie : missing) {
            if (progress != null) {
                // Мягкая пауза между фильмами (безопасная точка, не посреди операции с браузером).
                progress.waitWhilePaused();
                if (progress.isAborted()) {
                    log.info("Остановлен пользователем: загружено оригиналов {}/{}", done, missing.size());
                    break;
                }
            }
            try {
                String url = movie.getKpUrl() != null ? movie.getKpUrl()
                        : (movie.getKpId() != null ? "https://www.kinopoisk.ru/film/" + movie.getKpId() + "/" : null);
                if (url == null) {
                    // Без URL фильм фактически не обработан: без done и без advance.
                    continue;
                }
                driver.get(url);
                String original = pollOriginalTitle(driver);
                if (original != null) {
                    movie.setNameEn(original);
                    log.info("Оригинал [{}/{}]: '{}' -> '{}'", done + 1, missing.size(), movie.getName(), original);
                } else {
                    log.warn("Оригинал не найден [{}/{}]: '{}' ({})", done + 1, missing.size(), movie.getName(), url);
                }
                if (movie.getYear() == 0) {
                    int year = extractYearFromCurrentPage(driver);
                    if (year > 0) {
                        movie.setYear(year);
                        log.info("Год [{}/{}]: '{}' -> {}", done + 1, missing.size(), movie.getName(), year);
                    }
                }
            } catch (Exception e) {
                log.warn("Не удалось получить оригинал [{}/{}]: '{}': {}", done + 1, missing.size(), movie.getName(), e.getMessage());
            }
            done++;
            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, movie.getName(), "original " + done + "/" + missing.size());
            }
            // Промежуточный дамп каждые 5 обработанных фильмов.
            if (onBatch != null && done % 5 == 0) {
                onBatch.accept(movies);
            }
        }
        // Финальный дамп в конце фазы (в т.ч. при остановке пользователем).
        if (onBatch != null && done > 0) {
            onBatch.accept(movies);
        }
        log.info("Загрузка оригинальных названий завершена");
    }

    /**
     * Общее количество оценок пользователя: ссылка a[href*="movies/voted-watched"]
     * в футере, внутри неё первый span[class*="statValue"].
     *
     * @return количество оценок или null, если страница не загрузилась / счётчик не виден
     */
    public Integer fetchTotalRatings(WebDriver driver, Long userId) {
        return parser.fetchTotalRatings(driver, userId);
    }

    /**
     * Валидация годов на этапе парсинга: у фильмов с невалидным годом настоящий год
     * берётся со страницы фильма КП. Одна попытка на фильм, без ретраев: не открылось
     * или не нашлось — год остаётся 0 (фильм получит «неполные данные» при проставлении).
     * После дозапроса название прогоняется через {@link GluedYearTitleCleaner#stripTrailingGluedYear}:
     * склейка «Название22025» зачищается, если соответствует настоящему году.
     * Дамп сохраняется через {@code onBatch} каждые 5 фильмов и в конце фазы.
     */
    public void fixInvalidYears(List<MovieData> movies, WebDriver driver, ImportProgress progress,
                                java.util.function.Consumer<List<MovieData>> onBatch) {
        List<MovieData> invalid = new ArrayList<>();
        for (MovieData m : movies) {
            if (!KpYearValidator.isValidYear(m.getYear())) {
                invalid.add(m);
            }
        }
        if (invalid.isEmpty()) {
            log.info("Годы валидны у всех фильмов, проход не нужен");
            return;
        }
        log.info("Валидация годов со страниц фильмов: {} фильмов", invalid.size());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));

        int done = 0;
        for (MovieData movie : invalid) {
            if (progress != null) {
                // Мягкая пауза между фильмами (безопасная точка, не посреди операции с браузером).
                progress.waitWhilePaused();
                if (progress.isAborted()) {
                    log.info("Остановлен пользователем: валидировано годов {}/{}", done, invalid.size());
                    break;
                }
            }
            try {
                String url = movie.getKpUrl() != null ? movie.getKpUrl()
                        : (movie.getKpId() != null ? "https://www.kinopoisk.ru/film/" + movie.getKpId() + "/" : null);
                if (url == null) {
                    // Без URL фильм фактически не обработан: без advance.
                    continue;
                }
                driver.get(url);
                int year = extractYearFromCurrentPage(driver);
                if (KpYearValidator.isValidYear(year)) {
                    movie.setYear(year);
                    // Название могло быть склеено с годом, пока год был неизвестен:
                    // зачищаем склейку (хвост отрезается только при совпадении с настоящим годом).
                    if (movie.getName() != null) {
                        String stripped = GluedYearTitleCleaner.stripTrailingGluedYear(movie.getName(), year);
                        if (!stripped.equals(movie.getName())) {
                            log.info("Название '{}' было склеено с годом — исправлено на '{}' ({} г.)",
                                     movie.getName(), stripped, year);
                            movie.setName(stripped);
                        }
                    }
                    log.info("Год со страницы фильма [{}/{}]: '{}' (kpId={}) -> {}",
                             done + 1, invalid.size(), movie.getName(), movie.getKpId(), year);
                } else {
                    log.warn("Год не найден на странице фильма [{}/{}]: '{}' ({}) — оставляю 0",
                             done + 1, invalid.size(), movie.getName(), url);
                }
            } catch (Exception e) {
                log.warn("Не удалось валидировать год [{}/{}]: '{}': {}",
                         done + 1, invalid.size(), movie.getName(), e.getMessage());
            }
            done++;
            if (progress != null) {
                progress.advance(ImportProgress.PHASE_KP, movie.getName(), "year " + done + "/" + invalid.size());
            }
            // Промежуточный дамп каждые 5 обработанных фильмов.
            if (onBatch != null && done % 5 == 0) {
                onBatch.accept(movies);
            }
        }
        // Финальный дамп в конце фазы (в т.ч. при остановке пользователем).
        if (onBatch != null && done > 0) {
            onBatch.accept(movies);
        }
        log.info("Валидация годов завершена");
    }

    public List<MovieData> getNotes(Long userId) {
        List<MovieData> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<MovieData> pageMovies = parsePage(userId, page);
            result.addAll(pageMovies);
            if (pageMovies.size() < PER_PAGE) {
                break;
            }
        }
        return result;
    }

    /**
     * Сканирует страницы оценок КП с первой; после каждой обработанной страницы
     * (до {@value #PER_PAGE} фильмов) вызывает {@code onBatch} для промежуточного дампа.
     */
    public List<MovieData> getNotes(WebDriver driver, Long userId, ImportProgress progress,
                                    java.util.function.Consumer<List<MovieData>> onBatch) {
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        List<MovieData> result = new ArrayList<>();
        log.info("Начало сканирования оценок КП, пользователь {}, страницы с 1", userId);

        for (int page = 1; page <= MAX_PAGES; page++) {
            if (progress != null) {
                // Пауза/остановка проверяются в безопасной точке между страницами.
                progress.waitWhilePaused();
                if (progress.isAborted()) {
                    log.info("Импорт остановлен пользователем на странице {}", page);
                    break;
                }
            }

            String url = String.format(VOTES_URL_PAGE, userId, page);
            log.info("Сканирую страницу {}...", page);
            driver.get(url);

            List<MovieData> pageMovies;
            try {
                Document doc = parser.waitForPageLoad(driver);

                if (page == 1 && Boolean.parseBoolean(System.getProperty("kp.debugDump", "false"))) {
                    // Дамп HTML первой страницы — диагностика формата КП; по умолчанию выключен,
                    // чтобы не мусорить в рабочей директории (включается: -Dkp.debugDump=true).
                    try {
                        Files.write(Paths.get("kp-debug-page-1.html"),
                                    driver.getPageSource().getBytes(StandardCharsets.UTF_8));
                        log.debug("Флаг kp.debugDump активен — дамп HTML первой страницы сохранён в kp-debug-page-1.html");
                    } catch (IOException e) {
                        log.warn("Не удалось сохранить дамп HTML: {}", e.getMessage());
                    }
                }

                pageMovies = parser.parseDocument(doc);
                result.addAll(pageMovies);

                log.info("Страница {} успешно просканирована: найдено {} фильмов, распарсено {}",
                         page, pageMovies.size(), pageMovies.size());
            } catch (Exception e) {
                log.error("Ошибка при сканировании страницы {}: {}", page, e.getMessage(), e);
                continue;
            }

            if (progress != null) {
                // Фаза 1 (парсинг): каждая страница = 1 единица, знаменатель уже задан как «N + N/20».
                progress.advance(ImportProgress.PHASE_KP, "Страница " + page, "kp");
            }

            // Промежуточный дамп после каждой страницы (≈ каждые 20 фильмов).
            if (onBatch != null && !result.isEmpty()) {
                onBatch.accept(result);
            }

            if (pageMovies.size() < PER_PAGE) {
                log.info("На странице {} меньше {} фильмов — это последняя страница", page, PER_PAGE);
                break;
            }
        }

        log.info("Сканирование завершено. Всего найдено фильмов: {}", result.size());
        return result;
    }

    private List<MovieData> parsePage(Long userId, int page) {
        List<MovieData> movies;
        try {
            String url = String.format(VOTES_URL_PAGE, userId, page);
            Document doc = Jsoup.connect(url)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .timeout(15000)
                                .get();
            movies = parser.parseDocument(doc);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse KP page " + page, e);
        }
        return movies;
    }

    private String pollOriginalTitle(WebDriver driver) {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            try {
                for (WebElement el : driver.findElements(By.cssSelector("span[class*=\"originalTitle\"]"))) {
                    String text = el.getText().trim();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

}
