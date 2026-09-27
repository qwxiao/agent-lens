package io.agentlens.cli.eval;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parsed options of {@code agentlens eval run}. Flags are value-taking only, so the
 * parser stays a flat loop; validation errors surface as {@link UsageException} with
 * exit code 2 semantics.
 */
public record EvalArgs(String serverUrl, String datasetId, String name, String targetUrl,
                       List<String> scorers, Double gate, long timeoutSeconds) {

    static final String DEFAULT_SERVER = "http://localhost:8080";
    static final long DEFAULT_TIMEOUT_SECONDS = 600;
    private static final String SERVER_ENV = "AGENTLENS_URL";

    /** Thrown for bad usage or a --help request; {@link #help} separates exit code 2 from 0. */
    static final class UsageException extends Exception {
        final boolean help;

        UsageException(String message) {
            super(message);
            this.help = false;
        }

        UsageException(String message, boolean help) {
            super(message);
            this.help = help;
        }
    }

    static EvalArgs parse(String[] argv) throws UsageException {
        return parse(argv, System.getenv(SERVER_ENV));
    }

    static EvalArgs parse(String[] argv, String envServerUrl) throws UsageException {
        String server = envServerUrl == null || envServerUrl.isBlank() ? DEFAULT_SERVER : envServerUrl;
        String dataset = null;
        String name = null;
        String target = null;
        List<String> scorers = null;
        Double gate = null;
        long timeout = DEFAULT_TIMEOUT_SECONDS;

        for (int i = 0; i < argv.length; i++) {
            String flag = argv[i];
            if (!flag.startsWith("--") || i + 1 >= argv.length) {
                throw new UsageException("unknown or incomplete argument: " + flag);
            }
            String value = argv[++i];
            switch (flag) {
                case "--server" -> server = value;
                case "--dataset" -> dataset = value;
                case "--name" -> name = value;
                case "--target" -> target = value;
                case "--scorers" -> scorers = parseScorers(value);
                case "--gate" -> gate = parseGate(value);
                case "--timeout" -> timeout = parseTimeout(value);
                default -> throw new UsageException("unknown argument: " + flag);
            }
        }

        if (dataset == null || dataset.isBlank()) {
            throw new UsageException("--dataset is required");
        }
        if (target == null || target.isBlank()) {
            throw new UsageException("--target is required");
        }
        if (gate != null && (scorers == null || scorers.isEmpty())) {
            // without scorers every case passes vacuously server-side, so a gate on
            // an unscored run would always be green
            throw new UsageException("--gate requires --scorers");
        }
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            throw new UsageException("--server must be an http(s) URL: " + server);
        }
        return new EvalArgs(server.endsWith("/") ? server.substring(0, server.length() - 1) : server,
                dataset, name, target, scorers, gate, timeout);
    }

    private static List<String> parseScorers(String value) throws UsageException {
        List<String> names = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
        if (names.isEmpty()) {
            throw new UsageException("--scorers needs at least one name, e.g. exact_match,json_schema");
        }
        return names;
    }

    private static Double parseGate(String value) throws UsageException {
        Double gate;
        try {
            gate = Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--gate expects a number in (0,1], got: " + value);
        }
        if (gate <= 0 || gate > 1) {
            throw new UsageException("--gate expects a rate in (0,1], got: " + value);
        }
        return gate;
    }

    private static long parseTimeout(String value) throws UsageException {
        long seconds;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--timeout expects seconds, got: " + value);
        }
        if (seconds < 1) {
            throw new UsageException("--timeout must be at least 1 second, got: " + value);
        }
        return seconds;
    }

    /** The gate threshold as it is echoed back in messages, e.g. {@code 90.0%}. */
    String gateLabel() {
        return String.format(Locale.ROOT, "%.1f%%", gate * 100);
    }
}
