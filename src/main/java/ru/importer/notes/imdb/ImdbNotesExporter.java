package ru.importer.notes.imdb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;
import ru.importer.notes.dto.MovieData;
import ru.importer.notes.dto.MovieStatus;
import ru.importer.notes.movie.ImportProgress;
import ru.importer.notes.util.ErrorFormatter;

@Slf4j
@Service
public class ImdbNotesExporter {

    private static final Duration IMPLICIT_WAIT = Duration.ofSeconds(2);
    private static final Duration PAGE_WAIT = Duration.ofSeconds(10);
    private final ImdbRatingClicker clicker = new ImdbRatingClicker();
    private final ImdbPageVerifier verifier = new ImdbPageVerifier();
    private final ImdbSearchAndSelect search = new ImdbSearchAndSelect(verifier);

    /**
     * Непустые названия фильма (русское, оригинальное, английское).
     */
    static List<String> candidateTitles(MovieData movie) {
        List<String> titles = new ArrayList<>();
        for (String t : new String[]{movie.getName(), movie.getNameOriginal(), movie.getNameEn()}) {
            if (t != null && !t.isBlank()) {
                titles.add(t);
            }
        }
        return titles;
    }

    /**
     * Дедупликация списка по kp_id: остаётся первый фильм, дубли удаляются из списка.
     */
    static int dedupeByKpId(List<MovieData> movies) {
        Set<Long> seen = new HashSet<>();
        int removed = 0;
        Iterator<MovieData> it = movies.iterator();
        while (it.hasNext()) {
            MovieData movie = it.next();
            Long kpId = movie.getKpId();
            if (kpId == null) {
                continue;
            }
            if (!seen.add(kpId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Текущая оценка пользователя на странице фильма IMDB или null, если не оценён.
     */
    static Integer getExistingRating(WebDriver driver) {
        Duration prev = driver.manage().timeouts().getImplicitWaitTimeout();
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        try {
            try {
                WebElement score = driver.findElement(By.cssSelector(
                        "[data-testid=\"hero-rating-bar__user-rating__score\"] span"
                ));
                String text = score.getText().trim();
                if (!text.isEmpty()) {
                    try {
                        return Integer.parseInt(text.replaceAll("\\D", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                WebElement ratingBar = driver.findElement(By.cssSelector(
                        "[data-testid=\"hero-rating-bar__stars\"], " +
                                ".star-rating-button, " +
                                "[data-testid=\"rating-stars\"]"
                ));

                String alreadyRated = ratingBar.getAttribute("aria-label");
                if (alreadyRated != null && alreadyRated.matches(".*\\d+.*")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(alreadyRated);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }

                WebElement ratingValue = ratingBar.findElement(By.cssSelector(".ratingValue, [class*=\"rated\"]"));
                if (ratingValue != null) {
                    String text = ratingValue.getText().trim();
                    try {
                        return Integer.parseInt(text.replaceAll("\\D", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        } finally {
            driver.manage().timeouts().implicitlyWait(prev);
        }
    }

    /**
     * Совладельцы id — один тайтл (нормализованные названия совпали): сброс не нужен.
     */
    private static boolean isSharedBySameTitle(List<MovieData> sharing) {
        Set<String> normalizedTitles = new HashSet<>();
        for (MovieData movie : sharing) {
            normalizedTitles.add(primaryNormalizedTitle(movie));
        }
        return normalizedTitles.size() <= 1;
    }

    /**
     * Нормализация названия для сравнения: lowercase, пунктуация/символы — в пробелы,
     * артикль «the» выбрасывается. «The, Matrix!» и «the matrix» совпадут.
     */
    static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String[] tokens = title.toLowerCase(Locale.ROOT)
                               .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                               .trim()
                               .split(" ");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty() || "the".equals(token)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return sb.toString();
    }

    /**
     * Нормализованное «основное» название фильма: первое непустое из трёх названий.
     */
    private static String primaryNormalizedTitle(MovieData movie) {
        for (String title : new String[]{movie.getName(), movie.getNameOriginal(), movie.getNameEn()}) {
            if (title != null && !title.isBlank()) {
                return normalizeTitle(title);
            }
        }
        return "";
    }

    /**
     * Сбрасывает imdbId → PENDING у фильмов с одинаковым валидным imdb_id ({@code tt\d+}):
     * это глюк API КП — оценка могла уйти не тому тайтлу (известный кейс: подкаст-эпизод IMDB).
     * Исключение: нормализованные названия у всех совладельцев id совпадают — легитимная
     * двойная запись «фильм+сериал», сброс не нужен (иначе бесконечная перекачка).
     */
    static int resetDuplicatedImdbIds(List<MovieData> movies) {
        Map<String, List<MovieData>> byImdbId = new LinkedHashMap<>();
        for (MovieData movie : movies) {
            String imdbId = movie.getImdbId();
            if (ImdbPageVerifier.isValidImdbIdFormat(imdbId)) {
                byImdbId.computeIfAbsent(imdbId, k -> new ArrayList<>()).add(movie);
            }
        }
        int reset = 0;
        for (List<MovieData> sharing : byImdbId.values()) {
            if (sharing.size() < 2 || isSharedBySameTitle(sharing)) {
                continue;
            }
            for (MovieData movie : sharing) {
                movie.setImdbId(null);
                movie.setStatus(MovieStatus.PENDING);
                reset++;
            }
        }
        return reset;
    }

    static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Этап «Проставление»: ищет каждый фильм на IMDB и проставляет оценку.
     * Фильмы берутся из выбранного дампа kp-ratings-*.csv, уже обработанные (done) пропускаются.
     *
     * @param onMovieProcessed колбэк сохранения дампа — вызывается после каждых 5 фильмов,
     *                         чтобы статусы появлялись в дампе по ходу работы
     */
    public void evaluate(List<MovieData> movies, WebDriver driver, ImportProgress progress,
                         Runnable onMovieProcessed) {
        int duplicates = dedupeByKpId(movies);
        if (duplicates > 0) {
            log.info("IMDB import: удалено дублирующихся строк по kp_id: {} (осталось фильмов: {})",
                     duplicates, movies.size());
        }
        int brokenImdbIds = resetDuplicatedImdbIds(movies);
        if (brokenImdbIds > 0) {
            log.info("IMDB import: исправлено фильмов с задублированным imdb_id: {} — один и тот же id "
                             + "был у ≥2 разных фильмов (глюк API КП: под таким id мог скрываться не тот тайтл). "
                             + "imdb_id сброшен, статус PENDING — все такие фильмы переищутся по названию",
                     brokenImdbIds);
        }
        log.info("IMDB import: начинаем обработку {} фильмов", movies.size());

        driver.manage().timeouts().implicitlyWait(IMPLICIT_WAIT);

        WebDriverWait wait = new WebDriverWait(driver, PAGE_WAIT);

        progress.init(movies.size());

        Map<String, String> seenErrors = new HashMap<>();

        int processed = 0;

        for (int i = 0; i < movies.size(); i++) {
            if (progress.isAborted()) {
                log.info("IMDB import: остановлен пользователем на фильме {}/{}", i + 1, movies.size());
                break;
            }
            progress.waitWhilePaused();

            MovieData movie = movies.get(i);
            boolean alreadyDone = movie.getStatus().isDone();
            if (alreadyDone) {
                log.info("Фильм {}/{}: {} — уже обработан ранее ({}), пропускаю",
                         i + 1, movies.size(), movie.getName(), movie.getStatusLabel());
            } else {
                log.info("Фильм {}/{}: {} ({})", i + 1, movies.size(), movie.getName(), movie.getYear());
                // imdb_id строк ERROR/NOT_FOUND мог быть записан по ошибочному совпадению —
                // сбрасываем и ищем заново; id от API у PENDING сохраняем (ему можно доверять).
                if (movie.getStatus() == MovieStatus.ERROR || movie.getStatus() == MovieStatus.NOT_FOUND) {
                    movie.setImdbId(null);
                }
                movie.setErrorMessage(null);
                movie.setErrorDetails(null);
                // Неполные данные (нет названия или оценка 0) — на IMDB не ходим;
                // невалидный год блокером не является (стратегия поиска в processMovie).
                if (isIncompleteData(movie)) {
                    movie.setStatus(MovieStatus.INCOMPLETE_DATA);
                    log.info("Фильм {}/{}: {} — неполные данные (нет названия или оценки), "
                                     + "на IMDB не иду", i + 1, movies.size(), movie.getName());
                } else {
                    try {
                        processMovie(movie, driver, wait);
                    } catch (Exception e) {
                        movie.setStatus(MovieStatus.ERROR);
                        String fullMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                        String msg = truncateError(fullMsg);
                        movie.setErrorMessage(msg);
                        movie.setErrorDetails(ErrorFormatter.format(e));

                        String key = normalizeError(fullMsg);
                        String firstRef = seenErrors.get(key);
                        if (firstRef != null) {
                            log.info("Фильм {}/{}: повторная ошибка, впервые у {}: {}", i + 1, movies.size(), firstRef, msg);
                        } else {
                            seenErrors.put(key, "фильма №" + (i + 1) + " («" + movie.getName() + "»)");
                            // Последний аргумент-исключение: SLF4J печатает полный stack trace, а не только сообщение.
                            log.error("Фильм {}/{}: ошибка при обработке '{}': {}", i + 1, movies.size(), movie.getName(), msg, e);
                        }
                    }
                }
            }

            String displayName = movie.getYear() > 0
                    ? movie.getName() + " (" + movie.getYear() + ")"
                    : movie.getName();
            progress.advance(ImportProgress.PHASE_IMDB, displayName, movie.getStatus().name());

            if (++processed % 5 == 0) {
                onMovieProcessed.run();
            }

            if (i < movies.size() - 1 && !progress.isAborted() && !alreadyDone) {
                sleepRandom();
            }
        }

        log.info("IMDB import: завершено, обработано фильмов: {}", movies.size());
    }

    /**
     * Неполные данные: нет ни одного названия или оценка КП пустая (0) — по пустому
     * названию нашёлся бы посторонний фильм, а «Rate 0» на IMDB не существует.
     * Невалидный год неполными данными не считается (поиск по названию без года).
     */
    boolean isIncompleteData(MovieData movie) {
        boolean noTitle = (movie.getName() == null || movie.getName().isBlank())
                && (movie.getNameOriginal() == null || movie.getNameOriginal().isBlank())
                && (movie.getNameEn() == null || movie.getNameEn().isBlank());
        return noTitle || movie.getKpRating() <= 0;
    }

    private String normalizeError(String msg) {
        if (msg == null) {
            return null;
        }
        return msg
                .replaceAll("at point \\(\\d+, \\d+\\)", "at point (X, Y)")
                .replaceAll("aria-label=\"Rate \\d+\"", "aria-label=\"Rate N\"")
                .replaceAll("\\d+", "N");
    }

    /**
     * Обрабатывает один фильм: переход по imdbId (с верификацией страницы) или поиск,
     * проверка текущей оценки, проставление. Чужая страница, не-фильм (PodcastEpisode
     * и т.п.) или невалидный формат id — imdbId сбрасывается и выполняется поиск
     * по названию: оценку чужому фильму не ставим.
     */
    private void processMovie(MovieData movie, WebDriver driver, WebDriverWait wait) {
        String imdbId = movie.getImdbId();
        if (imdbId == null || imdbId.isBlank()) {
            search.searchAndOpen(driver, movie);
        } else if (!ImdbPageVerifier.isValidImdbIdFormat(imdbId)) {
            log.warn("imdbId '{}' невалидного формата (ожидался tt\\d+) — сбрасываю и ищу по названию", imdbId);
            movie.setImdbId(null);
            search.searchAndOpen(driver, movie);
        } else {
            log.info("Открываю IMDB напрямую по id: {}", imdbId);
            driver.get("https://www.imdb.com/title/" + imdbId + "/");
            if (!verifier.pageMatchesMovie(driver, movie)) {
                log.warn("Страница {} не соответствует фильму '{}' — сбрасываю imdbId и ищу по названию",
                         imdbId, movie.getName());
                movie.setImdbId(null);
                search.searchAndOpen(driver, movie);
            }
        }

        if (movie.getStatus() == MovieStatus.NOT_FOUND) {
            // Поиск не нашёл фильм (или результат неоднозначен) — оценку не ставим.
            return;
        }

        Integer existingRating = getExistingRating(driver);
        if (existingRating != null) {
            movie.setImdbRating(existingRating);
            if (existingRating == movie.getKpRating()) {
                movie.setStatus(MovieStatus.SKIPPED_SAME);
                log.info("Оценка уже стоит такая же ({}): {} ", existingRating, movie.getName());
            } else {
                movie.setStatus(MovieStatus.SKIPPED_DIFFERENT);
                log.info("Оценка отличается (IMDB={}, КП={}), оставляем руками: {}",
                         existingRating, movie.getKpRating(), movie.getName());
            }
            return;
        }

        log.info("Проставляю оценку {} для {}", movie.getKpRating(), movie.getName());
        clicker.setRating(driver, wait, movie.getKpRating());

        Integer confirmed = getExistingRating(driver);
        if (confirmed != null && confirmed == movie.getKpRating()) {
            movie.setImdbRating(confirmed);
            movie.setStatus(MovieStatus.RATED);
            log.info("Оценка {} подтверждена для {}", confirmed, movie.getName());
        } else {
            movie.setImdbRating(movie.getKpRating());
            movie.setStatus(MovieStatus.ERROR);
            movie.setErrorMessage("Оценка не подтвердилась на IMDB: ожидалось " + movie.getKpRating()
                                          + ", по факту " + (confirmed != null ? confirmed : "оценка не видна"));
            log.error("Оценка не подтвердилась для {}: ожидалось {}, по факту {}",
                      movie.getName(), movie.getKpRating(), confirmed);
        }
    }

    private void sleepRandom() {
        int seconds = ThreadLocalRandom.current().nextInt(1, 3);
        sleepUninterruptibly(seconds * 1000L);
    }

    private String truncateError(String msg) {
        if (msg == null) {
            return null;
        }
        int idx = msg.indexOf("\nBuild info:");
        if (idx > 0) {
            msg = msg.substring(0, idx);
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }

}
