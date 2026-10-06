package com.cinema.movie;

import com.cinema.common.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovieGenreTest {
    @Test
    void normalizesSeveralSelectedGenresAndRemovesDuplicates() {
        assertEquals("Action, Drama", MovieService.normalizeGenres(
                "action, Drama, Action", java.util.List.of("Action", "Drama")));
    }

    @Test
    void keepsExistingSlashSeparatedGenreValuesCompatible() {
        assertEquals("Action, Adventure", MovieService.normalizeGenres(
                "Action / Adventure", java.util.List.of("Action", "Adventure")));
    }

    @Test
    void rejectsEmptyAndUnknownGenreValues() {
        assertThrows(ServiceException.Validation.class, () -> MovieService.normalizeGenres(""));
        assertThrows(ServiceException.Validation.class, () -> MovieService.normalizeGenres("Made Up Genre"));
    }
}
