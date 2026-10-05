package com.cinema.movie;

import com.cinema.common.ServiceException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for centralized movie catalog management (Admin/Branch Manager writes, Req 2.1-2.6).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Create: title required, duration positive integer, rating in {P,C13,C16,C18},
 *       end_date >= release_date; stored as DRAFT or PUBLISHED (Req 2.1, 2.2).</li>
 *   <li>Update on a movie with future showtimes: description/poster/genre freely editable,
 *       duration/rating change blocked when it would break existing schedules (Req 2.3).</li>
 *   <li>Delete/deactivate blocked while future showtimes exist; the affected showtimes are
 *       listed in the rejection (Req 2.4).</li>
 *   <li>Scheduling catalog for Branch Manager/Staff: only PUBLISHED movies inside the
 *       effective date window (Req 2.5).</li>
 *   <li>Rating stored for age checks at booking/validation time (Req 2.6).</li>
 * </ul>
 */
public class MovieService {
    private static final Logger logger = Logger.getLogger(MovieService.class.getName());
    /** Req 15.3/11.2 — audit mọi thay đổi trạng thái movie. */
    private final com.cinema.audit.AuditService audit = new com.cinema.audit.AuditService();
    private final MovieDAO movieDao;
    private final GenreDAO genreDao;

    public MovieService(MovieDAO movieDAO) {
        this(movieDAO, new GenreDAO());
    }

    public MovieService(MovieDAO movieDAO, GenreDAO genreDAO) {
        this.movieDao = movieDAO;
        this.genreDao = genreDAO;
    }

    /**
     * Create a movie (Req 2.1, 2.2). Validates inputs, then persists with the
     * requested initial status (DRAFT or PUBLISHED; defaults to DRAFT).
     */
    public Movie createMovie(MovieInput input) throws Exception {
        validateCore(input);

        String status = input.status() == null || input.status().isBlank()
                ? Movie.STATUS_DRAFT : input.status().trim().toUpperCase();
        if (!Movie.STATUS_DRAFT.equals(status) && !Movie.STATUS_PUBLISHED.equals(status)) {
            throw new ServiceException.Validation(
                "Trạng thái khởi tạo phải là DRAFT hoặc PUBLISHED");
        }

        Movie movie = new Movie();
        movie.setTitle(input.title().trim());
        movie.setDurationMin(input.durationMin());
        movie.setGenre(normalizeGenres(input.genre(), genreDao));
        movie.setRating(input.rating().trim().toUpperCase());
        movie.setReleaseDate(input.releaseDate());
        movie.setEndDate(input.endDate());
        movie.setPosterUrl(input.posterUrl());
        movie.setDescription(input.description());
        movie.setAuthor(normalizeAuthor(input.author()));
        movie.setStatus(status);

        movieDao.insert(movie);
        logger.info("Created movie: " + movie.id() + " (" + status + ")");
        audit.recordSafely(null, "CREATE_MOVIE", "MOVIE", movie.id(), null,
                com.cinema.common.SerializationUtil.toJson(movie),
                com.cinema.audit.AuditService.SUCCESS);
        return movie;
    }

    /**
     * Update a movie (Req 2.3). When the movie still has future showtimes:
     * description/poster/genre may change, but duration/rating changes are blocked
     * because they would make existing showtimes violate duration or age-rating rules.
     */
    public Movie updateMovie(Long movieId, MovieInput input) throws Exception {
        validateCore(input);

        Movie existing = movieDao.findById(movieId)
            .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));

        List<MovieDAO.FutureShowtimeRef> futureShowtimes = movieDao.findFutureShowtimes(movieId);
        if (!futureShowtimes.isEmpty()) {
            boolean durationChanged = !existing.durationMin().equals(input.durationMin());
            boolean ratingChanged = !existing.rating().equalsIgnoreCase(input.rating().trim());
            if (durationChanged || ratingChanged) {
                throw new ServiceException.BusinessRule("MOVIE_HAS_FUTURE_SHOWTIME",
                    "Không thể sửa " + (durationChanged ? "thời lượng" : "phân loại độ tuổi")
                        + " vì phim còn lịch chiếu tương lai: " + describeShowtimes(futureShowtimes));
            }
        }

        existing.setTitle(input.title().trim());
        existing.setDurationMin(input.durationMin());
        existing.setGenre(normalizeGenres(input.genre(), genreDao));
        existing.setRating(input.rating().trim().toUpperCase());
        existing.setReleaseDate(input.releaseDate());
        existing.setEndDate(input.endDate());
        existing.setPosterUrl(input.posterUrl());
        existing.setDescription(input.description());
        existing.setAuthor(normalizeAuthor(input.author()));
        String status = input.status() == null || input.status().isBlank()
                ? existing.status() : input.status().trim().toUpperCase();
        if (!Movie.STATUS_DRAFT.equals(status)
                && !Movie.STATUS_PUBLISHED.equals(status)
                && !Movie.STATUS_ARCHIVED.equals(status)) {
            throw new ServiceException.Validation(
                    "Trạng thái phải là DRAFT, PUBLISHED hoặc ARCHIVED");
        }
        existing.setStatus(status);
        movieDao.update(existing);

        logger.info("Updated movie: " + movieId);
        audit.recordSafely(null, "UPDATE_MOVIE", "MOVIE", movieId, null,
                com.cinema.common.SerializationUtil.toJson(existing),
                com.cinema.audit.AuditService.SUCCESS);
        return existing;
    }

    /**
     * Publish a movie so branches can select it for scheduling (Req 2.5).
     */
    public Movie publishMovie(Long movieId) throws Exception {
        Movie movie = movieDao.findById(movieId)
            .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));

        if (Movie.STATUS_ARCHIVED.equals(movie.status())) {
            throw new ServiceException.BusinessRule("MOVIE_ARCHIVED",
                "Phim đã lưu trữ không thể phát hành lại");
        }
        movieDao.updateStatus(movieId, Movie.STATUS_PUBLISHED);
        movie.setStatus(Movie.STATUS_PUBLISHED);

        logger.info("Published movie: " + movieId);
        audit.recordSafely(null, "PUBLISH_MOVIE", "MOVIE", movieId, null,
                "{\"status\":\"PUBLISHED\"}", com.cinema.audit.AuditService.SUCCESS);
        return movie;
    }

    /**
     * Archive (soft-delete/deactivate) a movie (Req 2.4). Blocked while the movie
     * has future, not-yet-ended showtimes; the rejection lists them.
     */
    public Movie archiveMovie(Long movieId) throws Exception {
        Movie movie = movieDao.findById(movieId)
            .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));

        List<MovieDAO.FutureShowtimeRef> futureShowtimes = movieDao.findFutureShowtimes(movieId);
        if (!futureShowtimes.isEmpty()) {
            throw new ServiceException.BusinessRule("MOVIE_HAS_FUTURE_SHOWTIME",
                "Không thể xóa/vô hiệu hóa phim vì còn lịch chiếu tương lai: "
                    + describeShowtimes(futureShowtimes));
        }

        movieDao.updateStatus(movieId, Movie.STATUS_ARCHIVED);
        movie.setStatus(Movie.STATUS_ARCHIVED);

        logger.info("Archived movie: " + movieId);
        audit.recordSafely(null, "ARCHIVE_MOVIE", "MOVIE", movieId, null,
                "{\"status\":\"ARCHIVED\"}", com.cinema.audit.AuditService.SUCCESS);
        return movie;
    }

    /**
     * Full catalog for Admin and Branch Manager management screens.
     */
    public List<Movie> listAll() throws Exception {
        return movieDao.findAll();
    }

    /**
     * Paginated search with filters and sort for the management table.
     */
    public List<Movie> search(MovieDAO.MovieQuery q) throws Exception {
        if (q == null) {
            throw new ServiceException.Validation("Thiếu tham số truy vấn");
        }
        // Giới hạn limit/offset ở service để chặn request limit=999999 làm treo
        // SQL Server; mọi giá trị ngoài khoảng hợp lệ sẽ reset về default an toàn.
        if (q.limit <= 0 || q.limit > 200) {
            q.limit = 20;
        }
        if (q.offset < 0) {
            q.offset = 0;
        }
        return movieDao.search(q);
    }

    public Optional<Movie> findByIdForBranches(Long movieId, Set<Long> branchIds)
            throws Exception {
        return movieDao.findByIdForBranches(movieId, branchIds);
    }

    /** Count total movies matching the same filters (for pagination UI). */
    public long countSearch(MovieDAO.MovieQuery q) throws Exception {
        if (q == null) {
            return 0L;
        }
        return movieDao.countSearch(q);
    }

    /**
     * Catalog for Branch Manager/Branch Staff scheduling (Req 2.5): only
     * PUBLISHED movies within the effective screening window for the given date.
     */
    public List<Movie> listSelectableForScheduling(LocalDate date) throws Exception {
        LocalDate effective = date != null ? date : LocalDate.now();
        return movieDao.findSelectableForScheduling(effective);
    }

    public List<Movie> listSelectableForScheduling(LocalDate date, Set<Long> branchIds)
            throws Exception {
        LocalDate effective = date != null ? date : LocalDate.now();
        return movieDao.findSelectableForScheduling(effective, branchIds);
    }

    /**
     * Shared validation for create/update (Req 2.1, 2.2).
     */
    private void validateCore(MovieInput input) {
        if (input == null) {
            throw new ServiceException.Validation("Dữ liệu phim không được rỗng");
        }
        if (input.title() == null || input.title().trim().isEmpty()) {
            throw new ServiceException.Validation("Tiêu đề phim là bắt buộc");
        }
        if (input.durationMin() == null || input.durationMin() <= 0) {
            throw new ServiceException.Validation("Thời lượng phải là số nguyên dương (phút)");
        }
        normalizeGenres(input.genre(), genreDao);
        if (input.rating() == null || !Movie.VALID_RATINGS.contains(input.rating().trim().toUpperCase())) {
            throw new ServiceException.Validation(
                "Phân loại độ tuổi phải là một trong: P, C13, C16, C18");
        }

        if (input.releaseDate() == null) {
            throw new ServiceException.Validation("Ngày khởi chiếu là bắt buộc");
        }
        if (input.endDate() == null) {
            throw new ServiceException.Validation("Ngày kết thúc chiếu là bắt buộc");
        }
        if (input.endDate().isBefore(input.releaseDate())) {
            throw new ServiceException.Validation("Ngày kết thúc chiếu không được trước ngày khởi chiếu");
        }
        normalizeAuthor(input.author());
    }

    static String normalizeAuthor(String author) {
        if (author == null) return null;
        String normalized = author.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 200) {
            throw new ServiceException.Validation("Tác giả / đạo diễn không được vượt quá 200 ký tự");
        }
        return normalized;
    }

    static String normalizeGenres(String rawGenres) {
        return normalizeGenres(rawGenres, List.of("Action", "Adventure", "Drama"));
    }

    private String normalizeGenres(String rawGenres, GenreDAO genreDao) {
        try {
            return normalizeGenres(rawGenres, genreDao.findAll().stream().map(Genre::name).toList());
        } catch (Exception e) {
            throw new IllegalStateException("Không thể tải danh mục thể loại phim", e);
        }
    }

    static String normalizeGenres(String rawGenres, List<String> availableGenres) {
        if (rawGenres == null || rawGenres.isBlank()) {
            throw new ServiceException.Validation("Vui lòng chọn ít nhất một thể loại phim");
        }
        java.util.Map<String, String> canonical = availableGenres.stream().collect(
                java.util.stream.Collectors.toMap(g -> g.toLowerCase(), g -> g,
                        (first, second) -> first));
        java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
        for (String value : rawGenres.split("[,;/]")) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            String genre = canonical.get(trimmed.toLowerCase());
            if (genre == null) {
                throw new ServiceException.Validation("Thể loại không hợp lệ: " + trimmed);
            }
            selected.add(genre);
        }
        if (selected.isEmpty()) {
            throw new ServiceException.Validation("Vui lòng chọn ít nhất một thể loại phim");
        }
        return String.join(", ", selected);
    }

    private String describeShowtimes(List<MovieDAO.FutureShowtimeRef> refs) {
        return refs.stream()
            .map(r -> "[showtime " + r.showtimeId() + " @ " + r.branchName() + " " + r.startTime() + "]")
            .collect(Collectors.joining(", "));
    }

    /**
     * Input record for create/update (Req 2.1 field set).
     */
    public record MovieInput(String title, Integer durationMin, String genre, String rating,
                             LocalDate releaseDate, LocalDate endDate, String posterUrl,
                             String description, String author, String status) {
    }
}
