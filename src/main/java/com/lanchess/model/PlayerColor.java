package com.lanchess.model;

import java.io.Serializable;

/**
 * Merepresentasikan warna pemain / bidak dalam permainan catur.
 */
public enum PlayerColor implements Serializable {
    WHITE,
    BLACK;

    /**
     * @return warna lawan dari warna ini (dipakai untuk gantian giliran,
     *         cek serangan lawan, dsb.)
     */
    public PlayerColor opposite() {
        return this == WHITE ? BLACK : WHITE;
    }
}
