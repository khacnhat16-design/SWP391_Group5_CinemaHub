package com.cinema.movie;

import com.cinema.common.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovieAuthorTest {
    @Test
    void trimsPaddedAuthorValuesBeforePersistence() {
        assertEquals("tran anh hung", MovieService.normalizeAuthor(
                "tran anh hung" + " ".repeat(187)));
    }

    @Test
    void treatsBlankAuthorAsOptional() {
        assertNull(MovieService.normalizeAuthor("   "));
        assertNull(MovieService.normalizeAuthor(null));
    }

    @Test
    void rejectsAuthorsLongerThanTheDatabaseColumn() {
        assertThrows(ServiceException.Validation.class,
                () -> MovieService.normalizeAuthor("a".repeat(201)));
    }
}
