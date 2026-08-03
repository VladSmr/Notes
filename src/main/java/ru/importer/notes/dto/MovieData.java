package ru.importer.notes.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MovieData {

    private Long kpId;
    private String kpUrl;
    private String name;
    private String nameOriginal;
    private String nameEn;
    private int year;
    private int kpRating;
    private String imdbId;
    private Integer imdbRating;
    private String errorMessage;
    private MovieStatus status = MovieStatus.PENDING;

    /** Статус обработки фильма при импорте. */
    public enum MovieStatus {
        PENDING,
        NOT_FOUND,
        SKIPPED_SAME,
        SKIPPED_DIFFERENT,
        RATED,
        ERROR
    }

    /** Человекочитаемая подпись статуса для CSV. */
    public String getStatusLabel() {
        switch (status) {
            case RATED:
                return "успешно";
            case NOT_FOUND:
                return "не найден";
            case SKIPPED_SAME:
                return "пропущено (уже стоит оценка)";
            case SKIPPED_DIFFERENT:
                return "руками (оценки отличаются)";
            case ERROR:
                return "ошибка";
            default:
                return status.name();
        }
    }

    /** Восстанавливает статус из подписи, записанной в CSV (или PENDING для пустого/неизвестного). */
    public static MovieStatus parseStatusLabel(String label) {
        if (label == null || label.isBlank()) {
            return MovieStatus.PENDING;
        }
        switch (label.trim()) {
            case "успешно":
                return MovieStatus.RATED;
            case "не найден":
                return MovieStatus.NOT_FOUND;
            case "пропущено (уже стоит оценка)":
                return MovieStatus.SKIPPED_SAME;
            case "руками (оценки отличаются)":
                return MovieStatus.SKIPPED_DIFFERENT;
            case "ошибка":
                return MovieStatus.ERROR;
            default:
                return MovieStatus.PENDING;
        }
    }

}
