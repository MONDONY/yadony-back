package com.yadony.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransientLockRetryTest {

    @Test
    void returnsImmediately_whenNoConflict() {
        AtomicInteger calls = new AtomicInteger();
        String result = TransientLockRetry.run("op", () -> {
            calls.incrementAndGet();
            return "ok";
        }, conflict -> new IllegalStateException("jamais"));
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void replaysAfterADeadlock_thenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Boolean result = TransientLockRetry.run("op", () -> {
            if (calls.incrementAndGet() < TransientLockRetry.MAX_ATTEMPTS) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return true;
        }, conflict -> new IllegalStateException("jamais"));
        assertThat(result).isTrue();
        assertThat(calls).hasValue(TransientLockRetry.MAX_ATTEMPTS);
    }

    @Test
    void persistentConflict_throwsTheExhaustionException_afterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        CannotAcquireLockException last = new CannotAcquireLockException("deadlock detected");
        assertThatThrownBy(() -> TransientLockRetry.run("op", () -> {
            calls.incrementAndGet();
            throw last;
        }, conflict -> {
            assertThat(conflict).isSameAs(last);
            return new IllegalStateException("payment-busy");
        })).isInstanceOf(IllegalStateException.class).hasMessage("payment-busy");
        assertThat(calls).hasValue(TransientLockRetry.MAX_ATTEMPTS);
    }

    @Test
    void exhaustion_canRethrowTheConflictItself() {
        assertThatThrownBy(() -> TransientLockRetry.run("op", () -> {
            throw new PessimisticLockingFailureException("lock timeout");
        }, conflict -> conflict)).isInstanceOf(PessimisticLockingFailureException.class);
    }

    @Test
    void otherFailures_areNeverReplayed() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> TransientLockRetry.run("op", () -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("duplicate");
        }, conflict -> conflict)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptedPause_keepsTheInterruptFlag_andStillReplays() {
        AtomicInteger calls = new AtomicInteger();
        try {
            Boolean result = TransientLockRetry.run("op", () -> {
                if (calls.incrementAndGet() == 1) {
                    Thread.currentThread().interrupt();
                    throw new CannotAcquireLockException("deadlock detected");
                }
                return true;
            }, conflict -> conflict);
            assertThat(result).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
