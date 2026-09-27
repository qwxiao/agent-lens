package io.agentlens.cli;

import io.agentlens.cli.eval.EvalCommand;

import java.io.PrintStream;

/**
 * agentlens command line entry point. Subcommands are dispatched by hand — the CLI
 * stays a thin client over the platform API (ADR 0005), so there is no framework here.
 */
public final class AgentLensCli {

    static final String TOP_USAGE = """
            agentlens — command line for the agent-lens platform

            usage:
              agentlens <command> [arguments]

            commands:
              eval run   replay a dataset against a target agent and gate on the result
              help       show this help

            run 'agentlens eval run --help' for the eval options and exit codes.
            """;

    public static void main(String[] args) {
        System.exit(new AgentLensCli().run(args, System.out, System.err));
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.print(TOP_USAGE);
            return 2;
        }
        if (args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(TOP_USAGE);
            return 0;
        }
        if (!args[0].equals("eval")) {
            err.println("agentlens: unknown command: " + args[0]);
            err.println();
            err.print(TOP_USAGE);
            return 2;
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return new EvalCommand().run(rest, out, err);
    }
}
