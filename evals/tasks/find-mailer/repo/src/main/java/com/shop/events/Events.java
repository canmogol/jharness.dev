package com.shop.events;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class Events {
    private final List<BiConsumer<String, String>> listeners = new ArrayList<>();

    public void listen(BiConsumer<String, String> l) {
        listeners.add(l);
    }

    public void publish(String type, String value) {
        listeners.forEach(l -> l.accept(type, value));
    }
}
