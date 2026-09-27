package io.agentlens.server.eval;

/**
 * Strips the markdown code fences LLMs habitually wrap JSON in, so a reply like
 * {@code ```json\n{...}\n```} still validates as JSON.
 */
final class FencedJson {

    static String strip(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        String body = text.substring(firstNewline + 1);
        int closingFence = body.lastIndexOf("```");
        if (closingFence >= 0) {
            body = body.substring(0, closingFence);
        }
        return body.trim();
    }

    private FencedJson() {
    }
}
