package com.cinema.review;

import java.time.LocalDateTime;

/** Movie review entity (RSC-2). */
public class Review {
    private Long id;
    private Long movieId;
    private Long userId;
    private String userFullName;
    private int rating;
    private String comment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String movieTitle;

    public Review() {}

    public Long id() { return id; }
    public Long movieId() { return movieId; }
    public Long userId() { return userId; }
    public String userFullName() { return userFullName; }
    public int rating() { return rating; }
    public String comment() { return comment; }
    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }
    public String movieTitle() { return movieTitle; }

    public void setId(Long id) { this.id = id; }
    public void setMovieId(Long movieId) { this.movieId = movieId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setUserFullName(String userFullName) { this.userFullName = userFullName; }
    public void setRating(int rating) { this.rating = rating; }
    public void setComment(String comment) { this.comment = comment; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
}
