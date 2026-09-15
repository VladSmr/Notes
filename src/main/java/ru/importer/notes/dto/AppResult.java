package ru.importer.notes.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppResult {

    private String errorDetails;
    private String errorMessage;
    private int errors;
    private int incompleteData;
    private List<MovieData> movies;
    private int notFound;
    private int rated;
    private int skippedDifferent;
    private int skippedSame;
    private int totalMovies;

}
