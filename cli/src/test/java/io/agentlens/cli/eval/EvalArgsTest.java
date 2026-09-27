package io.agentlens.cli.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalArgsTest {

    private static final String[] MINIMAL = {"--dataset", "ds1", "--target", "http://agent/replay"};

    @Test
    void appliesDefaults() throws Exception {
        EvalArgs args = EvalArgs.parse(MINIMAL, null);
        assertEquals("http://localhost:8080", args.serverUrl());
        assertEquals(600, args.timeoutSeconds());
        assertEquals(null, args.scorers());
        assertEquals(null, args.gate());
        assertEquals(null, args.name());
    }

    @Test
    void envServerIsTheDefaultAndTrailingSlashIsTrimmed() throws Exception {
        EvalArgs args = EvalArgs.parse(MINIMAL, "http://platform:9000/");
        assertEquals("http://platform:9000", args.serverUrl());
    }

    @Test
    void parsesAllFlags() throws Exception {
        EvalArgs args = EvalArgs.parse(new String[]{
                "--server", "http://platform:9000", "--dataset", "ds1", "--name", "nightly",
                "--target", "http://agent/replay", "--scorers", " exact_match , json_schema ",
                "--gate", "0.9", "--timeout", "30"}, null);
        assertEquals("http://platform:9000", args.serverUrl());
        assertEquals("nightly", args.name());
        assertEquals(List.of("exact_match", "json_schema"), args.scorers());
        assertEquals(0.9, args.gate());
        assertEquals(30, args.timeoutSeconds());
    }

    @Test
    void rejectsMissingRequiredFlags() {
        assertThrows(EvalArgs.UsageException.class, () -> EvalArgs.parse(new String[0], null));
        assertThrows(EvalArgs.UsageException.class,
                () -> EvalArgs.parse(new String[]{"--dataset", "ds1"}, null));
        assertThrows(EvalArgs.UsageException.class,
                () -> EvalArgs.parse(new String[]{"--target", "http://agent"}, null));
    }

    @Test
    void rejectsGateWithoutScorers() {
        assertThrows(EvalArgs.UsageException.class,
                () -> EvalArgs.parse(new String[]{"--dataset", "d", "--target", "t", "--gate", "0.9"}, null));
    }

    @Test
    void rejectsGateOutOfRange() {
        for (String bad : new String[]{"0", "1.2", "abc", "-0.5"}) {
            assertThrows(EvalArgs.UsageException.class, () -> EvalArgs.parse(new String[]{
                    "--dataset", "d", "--target", "t", "--scorers", "exact_match", "--gate", bad}, null),
                    "gate=" + bad);
        }
    }

    @Test
    void rejectsUnknownAndIncompleteFlags() {
        assertThrows(EvalArgs.UsageException.class,
                () -> EvalArgs.parse(new String[]{"--bogus", "x", "--dataset", "d", "--target", "t"}, null));
        assertThrows(EvalArgs.UsageException.class,
                () -> EvalArgs.parse(new String[]{"--dataset"}, null));
    }

    @Test
    void rejectsBadTimeoutAndBadServer() {
        assertThrows(EvalArgs.UsageException.class, () -> EvalArgs.parse(new String[]{
                "--dataset", "d", "--target", "t", "--timeout", "0"}, null));
        assertThrows(EvalArgs.UsageException.class, () -> EvalArgs.parse(new String[]{
                "--dataset", "d", "--target", "t", "--server", "platform:8080"}, null));
    }

    @Test
    void gateLabelFormatsAsPercent() throws Exception {
        EvalArgs args = EvalArgs.parse(new String[]{
                "--dataset", "d", "--target", "t", "--scorers", "exact_match", "--gate", "0.9"}, null);
        assertTrue(args.gateLabel().endsWith("%"));
    }
}
