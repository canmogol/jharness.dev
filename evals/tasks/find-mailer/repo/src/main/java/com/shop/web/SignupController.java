package com.shop.web;

import com.shop.account.AccountService;

public final class SignupController {
    private final AccountService accounts;

    public SignupController(AccountService accounts) {
        this.accounts = accounts;
    }

    public String post(String email, String password) {
        accounts.register(email, password);
        return "redirect:/welcome";
    }
}
