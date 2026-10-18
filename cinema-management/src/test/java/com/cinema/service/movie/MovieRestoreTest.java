package com.cinema.service.movie;

import com.cinema.common.ServiceException;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.model.movie.Movie;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MovieRestoreTest {

    @Test
    void restoresArchivedMovieToPublished() throws Exception {
        Movie movie = new Movie();
        movie.setId(23L);
        movie.setStatus(Movie.STATUS_ARCHIVED);
        TestMovieDAO movieDao = new TestMovieDAO(movie);

        Movie restored = new MovieService(movieDao).restoreMovie(23L);

        assertEquals(Movie.STATUS_PUBLISHED, restored.status());
        assertEquals(Movie.STATUS_PUBLISHED, movieDao.updatedStatus);
    }

    @Test
    void restoreRejectsMovieThatIsNotArchived() {
        Movie movie = new Movie();
        movie.setId(23L);
        movie.setStatus(Movie.STATUS_PUBLISHED);

        ServiceException.BusinessRule error = assertThrows(
                ServiceException.BusinessRule.class,
                () -> new MovieService(new TestMovieDAO(movie)).restoreMovie(23L));

        assertEquals("MOVIE_NOT_ARCHIVED", error.code());
    }

    private static final class TestMovieDAO extends MovieDAO {
        private final Movie movie;
        private String updatedStatus;

        private TestMovieDAO(Movie movie) {
            this.movie = movie;
        }

        @Override
        public Optional<Movie> findById(Long id) {
            return Optional.of(movie);
        }

        @Override
        public void updateStatus(Long id, String status) {
            updatedStatus = status;
        }
    }
}
