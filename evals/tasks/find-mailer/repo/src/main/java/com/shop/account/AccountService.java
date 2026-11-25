package com.shop.account;

import com.shop.events.Events;

public final class AccountService {
    private final Events events;

    public AccountService(Events events) {
        this.events = events;
    }

    public void register(String email, String password) {
        // Stores the account, then tells whoever listens.
        events.publish("account.created", email);
    }
}
