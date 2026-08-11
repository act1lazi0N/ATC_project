package com.actilazion.aries_transaction.identity.application;

import java.time.Duration;

public interface LoginAttemptStore {
    void ensureAvailable();

    long recordFailure(String key, Duration ttl);

    void clear(String key);
}
