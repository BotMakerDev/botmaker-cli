package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyPassTest {

    /** Nine rows, the nine JitPack artifacts of a full release, in tag order. */
    private static List<ReleaseLog.Row> nineRows() {
        Map<Module, Version> releasing = new EnumMap<>(Module.class);
        for (Module module : Module.values()) {
            if (module.onJitpack()) {
                releasing.put(module, new Version(0, 1, 0));
            }
        }
        return ReleaseLog.rows(releasing);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void rowsComeBackInTheirOwnOrderWhateverFinishesFirst() {
        List<ReleaseLog.Row> rows = nineRows();

        // The first row is the slowest, so a pass that collected in completion order would put it last.
        List<ReleaseLog.Row> done = VerifyPass.run(new Runner(false, line -> { }), rows, (own, row) -> {
            sleep(row == rows.get(0) ? 300 : 10);
            return row.withActions("success (1)", "");
        });

        assertEquals(rows.stream().map(ReleaseLog.Row::module).toList(),
                done.stream().map(ReleaseLog.Row::module).toList());
        assertTrue(done.stream().allMatch(row -> row.actions().equals("success (1)")));
    }

    @Test
    void narrationIsGroupedPerModuleAndInRowOrder() {
        List<ReleaseLog.Row> rows = nineRows();
        List<String> said = new ArrayList<>();

        VerifyPass.run(new Runner(false, said::add), rows, (own, row) -> {
            own.say(row.module().directory() + " start");
            // Later rows finish sooner, which is what interleaves an unbuffered pass.
            sleep(20L * (rows.size() - rows.indexOf(row)));
            own.say(row.module().directory() + " end");
            return row;
        });

        List<String> expected = new ArrayList<>();
        for (ReleaseLog.Row row : rows) {
            expected.add(row.module().directory() + " start");
            expected.add(row.module().directory() + " end");
        }
        assertEquals(expected, said);
    }

    @Test
    void atMostTheCapRunsAtOnceAndMoreThanOneDoes() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();

        VerifyPass.run(new Runner(false, line -> { }), nineRows(), (own, row) -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(50);
            running.decrementAndGet();
            return row;
        });

        assertTrue(most.get() > 1, "the pass ran serially");
        assertTrue(most.get() <= VerifyPass.PARALLEL, "more than " + VerifyPass.PARALLEL + " at once");
    }

    @Test
    void aCheckThatThrowsBecomesItsRowsCellAndTheOthersStillAnswer() {
        List<ReleaseLog.Row> rows = nineRows();

        List<ReleaseLog.Row> done = VerifyPass.run(new Runner(false, line -> { }), rows, (own, row) -> {
            if (row == rows.get(2)) {
                throw new IllegalStateException("gh exploded");
            }
            return row.withActions("success (1)", "");
        });

        assertEquals(rows.size(), done.size());
        assertEquals("unknown (the check failed)", done.get(2).actions());
        assertEquals("gh exploded", done.get(2).actionsError());
        assertEquals("success (1)", done.get(3).actions());
    }
}
