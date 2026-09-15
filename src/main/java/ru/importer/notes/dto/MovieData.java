package ru.importer.notes.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MovieData {

    private String errorDetails;
    private String errorMessage;
    private String imdbId;
    private Integer imdbRating;
    private Long kpId;
    private int kpRating;
    private String kpUrl;
    private String name;
    private String nameEn;
    private String nameOriginal;
    private MovieStatus status = MovieStatus.PENDING;
    private int year;

    /**
     * Восстанавливает статус из подписи, записанной в CSV (или PENDING для пустого/неизвестного).
     */
    public static MovieStatus parseStatusLabel(String label) {
        if (label == null || label.isBlank()) {
            return MovieStatus.PENDING;
        }
        return switch (label.trim()) {
            case "успешно" -> MovieStatus.RATED;
            case "не найден" -> MovieStatus.NOT_FOUND;
            case "пропущено (уже стоит оценка)" -> MovieStatus.SKIPPED_SAME;
            case "руками (оценки отличаются)" -> MovieStatus.SKIPPED_DIFFERENT;
            case "ошибка" -> MovieStatus.ERROR;
            case "неполные данные" -> MovieStatus.INCOMPLETE_DATA;
            default -> MovieStatus.PENDING;
        };
    }

    /**
     * Человекочитаемая подпись статуса для CSV.
     */
    public String getStatusLabel() {
        return switch (status) {
            case RATED -> "успешно";
            case NOT_FOUND -> "не найден";
            case SKIPPED_SAME -> "пропущено (уже стоит оценка)";
            case SKIPPED_DIFFERENT -> "руками (оценки отличаются)";
            case ERROR -> "ошибка";
            case INCOMPLETE_DATA -> "неполные данные";
            default -> status.name();
        };
    }

}
