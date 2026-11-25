package com.shop.mail;

import com.shop.events.Events;

public final class OnboardingListener {
    public OnboardingListener(Events events, GreetingSender sender) {
        events.listen((type, email) -> {
            if (type.equals("account.created")) {
                sender.send(email);
            }
        });
    }
}
