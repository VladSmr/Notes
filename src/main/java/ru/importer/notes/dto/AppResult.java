package ru.importer.notes.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppResult {

    private int totalMovies;
    private int rated;
    private int notFound;
    private int skippedSame;
    private int skippedDifferent;
    private int errors;
    private List<MovieData> movies;
    private String errorMessage;

}
