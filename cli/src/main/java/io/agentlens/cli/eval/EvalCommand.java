package io.agentlens.cli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.agentlens.cli.eval.EvalArgs.UsageException;

/**
 * {@code agentlens eval run}: starts a run through the platform API, polls until it
 * settles, then turns the outcome into the CI exit code contract of ADR 0005 —
 * 0 gate met, 1 gate not met, 2 operational failure.
 */
public final class EvalCommand {

    static final String USAGE = """
            agentlens eval run — replay a dataset against a target agent and gate on the results

            usage:
              agentlens eval run --dataset <id> --target <url> [--gate <rate>] [options]

            required:
              --dataset <id>      dataset id on the platform to replay
              --target <url>      target agent replay endpoint, receiving
                                  {"run_id", "case_id", "input"} (ADR 0004)

            options:
              --server <url>      agent-lens base URL (default $AGENTLENS_URL or http://localhost:8080)
              --scorers <list>    comma-separated scorer names: exact_match, json_schema, llm_judge
                                  (required when --gate is set)
              --name <name>       run name shown on the dashboard
              --gate <rate>       minimum pass rate in (0,1]; exits 1 below it (e.g. --gate 0.9)
              --timeout <sec>     max seconds to wait for the run to settle (default 600)
              --help              show this help

            exit codes:
              0  run completed and the gate is met (or no gate requested)
              1  run completed but the gate is not met
              2  bad usage, platform unreachable, run failed, or timed out
            """;

    private static final long POLL_INTERVAL_MS = 1_000;

    public int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !args[0].equals("run")) {
            err.println("agentlens: unknown eval command: "
                    + (args.length == 0 ? "(none)" : args[0]));
            err.println();
            err.print(USAGE);
            return 2;
        }
        String[] flags = new String[args.length - 1];
        System.arraycopy(args, 1, flags, 0, flags.length);
        for (String arg : flags) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(USAGE);
                return 0;
            }
        }
        EvalArgs eval;
        try {
            eval = EvalArgs.parse(flags);
        } catch (UsageException e) {
            err.println("agentlens: " + e.getMessage());
            err.println();
            err.print(USAGE);
            return 2;
        }
        PlatformClient client = new PlatformClient(eval.serverUrl());
        try {
            return execute(eval, client, out, err);
        } catch (IOException e) {
            err.println("agentlens: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("agentlens: interrupted while waiting for the run");
            return 2;
        }
    }

    private int execute(EvalArgs eval, PlatformClient client, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        String runId = client.startRun(new PlatformClient.StartRequest(
                eval.datasetId(), eval.name(), eval.targetUrl(), eval.scorers()));
        out.printf(Locale.ROOT, "run %s started — dataset %s → %s%n",
                runId, eval.datasetId(), eval.targetUrl());
        RunDetail detail = awaitSettled(eval, client, runId, out, err);
        if (detail == null) {
            return 2;
        }
        return report(detail, eval, out, err);
    }

    private RunDetail awaitSettled(EvalArgs eval, PlatformClient client, String runId,
                                   PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + eval.timeoutSeconds() * 1_000_000_000L;
        while (true) {
            RunDetail detail = client.getRun(runId);
            String status = detail.run() == null ? "" : detail.run().status();
            if (!"running".equals(status)) {
                return detail;
            }
            if (System.nanoTime() >= deadline) {
                err.println("agentlens: run " + runId + " is still running after "
                        + eval.timeoutSeconds() + "s — check " + eval.serverUrl()
                        + "/api/eval-runs/" + runId);
                return null;
            }
            out.flush();
            Thread.sleep(Math.min(POLL_INTERVAL_MS,
                    Math.max(1, (deadline - System.nanoTime()) / 1_000_000L)));
        }
    }

    /** Maps a settled run to the exit code and writes the CI-visible summary. */
    int report(RunDetail detail, EvalArgs eval, PrintStream out, PrintStream err) {
        String status = detail.run() == null ? "" : detail.run().status();
        if ("failed".equals(status)) {
            err.println("agentlens: run failed on the platform — check " + eval.serverUrl()
                    + "/api/eval-runs/" + (detail.run() == null ? "?" : detail.run().id()));
            return 2;
        }
        if (!"completed".equals(status)) {
            err.println("agentlens: run ended in unexpected status '" + status + "'");
            return 2;
        }
        RunDetail.Totals totals = detail.totals();
        if (totals == null) {
            err.println("agentlens: platform returned no totals for the run");
            return 2;
        }
        if (totals.caseCount() == 0) {
            err.println("agentlens: dataset " + eval.datasetId()
                    + " has no cases — nothing to evaluate");
            return 2;
        }

        out.println("run " + detail.run().id() + " completed: " + totals.passedCount() + "/"
                + totals.caseCount() + " cases passed (" + percent(totals.passRate())
                + "), avg latency " + (totals.avgLatencyMs() == null ? "n/a" : totals.avgLatencyMs() + " ms")
                + (totals.costUsd() == null ? "" : ", cost $" + totals.costUsd().setScale(4, RoundingMode.HALF_UP).toPlainString()));

        for (String line : failureLines(detail)) {
            out.println(line);
        }

        if (eval.gate() == null) {
            return 0;
        }
        if (totals.passRate() >= eval.gate()) {
            out.println("gate " + eval.gateLabel() + " met");
            return 0;
        }
        err.println("gate " + eval.gateLabel() + " not met — pass rate "
                + percent(totals.passRate()) + " is below " + eval.gateLabel());
        return 1;
    }

    private static List<String> failureLines(RunDetail detail) {
        List<String> lines = new ArrayList<>();
        if (detail.cases() == null) {
            return lines;
        }
        for (RunDetail.CaseView entry : detail.cases()) {
            if (Boolean.TRUE.equals(entry.passed())) {
                continue;
            }
            String reason = entry.error() != null ? entry.error() : scorerFailures(entry.scores());
            lines.add("  failed " + entry.caseId() + ": " + reason);
        }
        return lines;
    }

    /** Joins the {@code detail} of every scorer that failed on this case. */
    private static String scorerFailures(JsonNode scores) {
        List<String> details = new ArrayList<>();
        if (scores != null) {
            scores.fields().forEachRemaining(field -> {
                if (!field.getValue().path("passed").asBoolean(true)) {
                    details.add(field.getKey() + ": " + field.getValue().path("detail").asText("no detail"));
                }
            });
        }
        return details.isEmpty() ? "no passing scorer" : String.join("; ", details);
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
