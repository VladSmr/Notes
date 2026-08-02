package ru.importer.notes.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InputData {

    /** Идентификатор пользователя Кинопоиска. */
    private Long kpUserId;

    /** Путь к директории для лог-файлов. */
    private String logDirectory;

    /** Способ парсинга КП: selenium или api. */
    private String parserType;

    /** API-токен Кинопоиска (обязателен при parserType=api). */
    private String apiToken;

    /** Читать данные из сохранённого дампа kp-ratings.csv, если он есть. */
    private Boolean useSavedDump;

}
