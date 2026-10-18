package com.cinema.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CinemaApplicationTest {
    @Test
    void resolvePortSkipsBlankOverrides() {
        assertEquals(8123, CinemaApplication.resolvePort("", "  ", " 8123 ", "8080"));
    }

    @Test
    void resolvePortUsesDefaultWhenNoOverrideIsSet() {
        assertEquals(8080, CinemaApplication.resolvePort(null, "", " "));
    }
}
