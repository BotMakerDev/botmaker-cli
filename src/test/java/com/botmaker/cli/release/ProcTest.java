package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcTest {

    @Test
    void capturesTheOutputAndTheExitCode() {
        Proc.Result result = Proc.run(Path.of("."), "sh", "-c", "echo out; echo err >&2; exit 3");

        assertEquals(3, result.exit());
        assertTrue(result.out().contains("out"));
        assertTrue(result.out().contains("err"));
    }

    @Test
    void anInterruptStopsTheCommandAtOnce() throws InterruptedException {
        // A dashboard Cancel is an interrupt of the thread running a preview. The command it lands in has to
        // end then, not after its own thirty seconds.
        AtomicReference<Proc.Result> answer = new AtomicReference<>();
        Thread running = Thread.ofVirtual().start(() -> answer.set(Proc.run(Path.of("."), "sleep", "30")));
        Thread.sleep(300);
        Instant asked = Instant.now();
        running.interrupt();
        running.join(Duration.ofSeconds(5));

        assertFalse(running.isAlive());
        assertEquals(Proc.INTERRUPTED, answer.get().exit());
        assertTrue(Duration.between(asked, Instant.now()).toSeconds() < 5);
    }

    @Test
    void anInterruptedThreadStartsNothing() {
        // What makes a cancelled pass unwind: every later step answers at once instead of running.
        Thread.currentThread().interrupt();
        try {
            Proc.Result result = Proc.run(Path.of("."), "sh", "-c", "echo ran");
            assertEquals(Proc.INTERRUPTED, result.exit());
            assertFalse(result.out().contains("ran"));
        } finally {
            Thread.interrupted();
        }
    }
}
