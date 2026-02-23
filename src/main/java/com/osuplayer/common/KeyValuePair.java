package com.osuplayer.common;

public class KeyValuePair<Key, Value> {
    private final Key key;
    private final Value value;

    public KeyValuePair(Key key, Value value) {
        this.key = key;
        this.value = value;
    }

    public Key key() {
        return key;
    }

    public Value value() {
        return value;
    }
}
