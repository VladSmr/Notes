package ru.importer.notes.dto;

import lombok.Value;

@Value
public class ProgressEvent {
    String phase;
    int current;
    int total;
    String movieName;
    String status;
    boolean finished;
    AppResult result;
    boolean paused;
}
