package com.lanchess.model;

import java.io.Serializable;

/**
 * Command Pattern: setiap aksi antara client<->server dibungkus sebagai
 * Message dan dikirim lewat ObjectOutputStream/ObjectInputStream.
 *
 * payload harus di-cast sesuai MessageType (lihat dokumentasi di
 * MessageType.java untuk tipe payload masing-masing MessageType).
 */
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;


    private final MessageType type;
    private final Object payload;
    private final String sender;

    public Message(MessageType type, Object payload, String sender) {
        this.type = type;
        this.payload = payload;
        this.sender = sender;
    }

    /** Konstruktor pendek untuk pesan tanpa identitas sender eksplisit (mis. dari server). */
    public Message(MessageType type, Object payload) {
        this(type, payload, "SERVER");
    }

    public MessageType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    /**
     * Helper generic supaya pemanggil tidak perlu cast manual berulang-ulang.
     * Contoh: Move move = message.getPayloadAs(Move.class);
     */
    public <T> T getPayloadAs(Class<T> clazz) {
        return clazz.cast(payload);
    }

    public String getSender() {
        return sender;
    }

    @Override
    public String toString() {
        return "Message{type=%s, sender=%s, payload=%s}".formatted(type, sender, payload);
    }
}
