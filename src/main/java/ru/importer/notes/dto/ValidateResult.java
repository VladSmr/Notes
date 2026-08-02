package ru.importer.notes.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ValidateResult {

    private String errorMessage;
    private boolean hasError = false;

}
