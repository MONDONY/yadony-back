package com.yadony.api.calls;

public enum CallStatus {
    RINGING, ANSWERED, ENDED, MISSED, REJECTED;

    public boolean isTerminal() {
        return this == ENDED || this == MISSED || this == REJECTED;
    }
}
