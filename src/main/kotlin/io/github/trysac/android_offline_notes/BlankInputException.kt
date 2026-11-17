package io.github.trysac.android_offline_notes

/**
 * Thrown when a required text input is blank.
 */
public class BlankInputException :
    NotesException(
        code = "blank_input",
        message = "input is blank",
    )
