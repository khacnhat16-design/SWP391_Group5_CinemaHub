package com.cinema.model.catalog;

public record MovieCard(long id, String title, String genre, int durationMinutes,
                        String rating, String imagePath, String description) { }
