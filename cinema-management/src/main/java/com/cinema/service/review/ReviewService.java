package com.cinema.service.review;

import com.cinema.dao.review.ReviewDAO;
import com.cinema.model.review.Review;

import com.cinema.common.ServiceException;

import java.util.List;

/** Service layer for movie reviews (RSC-2). */
public class ReviewService {

    private final ReviewDAO dao;

    public ReviewService(ReviewDAO dao) {
        this.dao = dao;
    }

    public Review upsert(long userId, long movieId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new ServiceException.Validation("Vui lòng chọn số sao từ 1 đến 5");
        }
        String c = comment == null ? null : comment.trim();
        if (c != null && c.length() > 1000) {
            throw new ServiceException.Validation("Bình luận không được vượt quá 1000 ký tự");
        }
        try {
            dao.upsert(userId, movieId, rating, c);
            return dao.findByUserAndMovie(userId, movieId)
                    .orElseThrow(() -> new ServiceException.BusinessRule("REVIEW_NOT_FOUND", "Không thể lưu review"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_PERSIST_FAILED",
                    "Lỗi khi lưu review: " + e.getMessage());
        }
    }

    public List<Review> listByMovie(long movieId, int offset, int limit) {
        try {
            return dao.findByMovie(movieId, Math.max(offset, 0), Math.min(Math.max(limit, 1), 100));
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_LIST_FAILED",
                    "Lỗi khi tải danh sách review: " + e.getMessage());
        }
    }

    public long countByMovie(long movieId) {
        try { return dao.countByMovie(movieId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_COUNT_FAILED", e.getMessage());
        }
    }

    public double averageByMovie(long movieId) {
        try { return dao.averageByMovie(movieId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_AVG_FAILED", e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Manager: xem tất cả review của phim đã/đang chiếu tại một chi nhánh.
    // ---------------------------------------------------------------------
    public List<Review> listByBranch(long branchId, int offset, int limit) {
        try {
            return dao.findByBranch(branchId, Math.max(offset, 0), Math.min(Math.max(limit, 1), 200));
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_LIST_BRANCH_FAILED", e.getMessage());
        }
    }

    public List<Review> listByBranchAndMovie(long branchId, long movieId, int offset, int limit) {
        try {
            return dao.findByBranchAndMovie(
                    branchId,
                    movieId,
                    Math.max(offset, 0),
                    Math.min(Math.max(limit, 1), 100));
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_LIST_BRANCH_MOVIE_FAILED", e.getMessage());
        }
    }

    public List<java.util.Map<String, Object>> movieSummariesByBranch(long branchId) {
        try {
            return dao.movieSummariesByBranch(branchId);
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_MOVIE_SUMMARIES_FAILED", e.getMessage());
        }
    }

    public long countByBranchAndMovie(long branchId, long movieId) {
        try {
            return dao.countByBranchAndMovie(branchId, movieId);
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_COUNT_BRANCH_MOVIE_FAILED", e.getMessage());
        }
    }

    public long countByBranch(long branchId) {
        try { return dao.countByBranch(branchId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_COUNT_BRANCH_FAILED", e.getMessage());
        }
    }

    public double averageByBranch(long branchId) {
        try { return dao.averageByBranch(branchId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_AVG_BRANCH_FAILED", e.getMessage());
        }
    }

    public java.util.Map<Long, ReviewDAO.ReviewStats> statsByBranch(long branchId) {
        try { return dao.statsByBranch(branchId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_STATS_BRANCH_FAILED", e.getMessage());
        }
    }

    public void deleteOwn(long reviewId, long userId) {
        try {
            boolean ok = dao.deleteByIdAndUser(reviewId, userId);
            if (!ok) {
                // Either not found or not owner → treat as forbidden to avoid leaking existence.
                throw new ServiceException.Forbidden("Bạn không thể xóa review này");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("REVIEW_DELETE_FAILED", e.getMessage());
        }
    }
}
