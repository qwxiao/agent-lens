package io.agentlens.server.eval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Evaluation engine settings. Secrets and endpoints stay out of the repo: the LLM
 * judge is configured through the {@code JUDGE_*} environment variables (ADR 0004).
 */
@Component
public class EvalProperties {

    private final long replayTimeoutMs;
    private final String judgeBaseUrl;
    private final String judgeApiKey;
    private final String judgeModel;

    public EvalProperties(
            @Value("${agentlens.eval.replay-timeout-ms:60000}") long replayTimeoutMs,
            @Value("${agentlens.eval.judge.base-url:}") String judgeBaseUrl,
            @Value("${agentlens.eval.judge.api-key:}") String judgeApiKey,
            @Value("${agentlens.eval.judge.model:}") String judgeModel) {
        this.replayTimeoutMs = replayTimeoutMs;
        this.judgeBaseUrl = judgeBaseUrl;
        this.judgeApiKey = judgeApiKey;
        this.judgeModel = judgeModel;
    }

    public long replayTimeoutMs() {
        return replayTimeoutMs;
    }

    public String judgeBaseUrl() {
        return judgeBaseUrl;
    }

    public String judgeApiKey() {
        return judgeApiKey;
    }

    public String judgeModel() {
        return judgeModel;
    }

    public boolean judgeConfigured() {
        return !judgeBaseUrl.isBlank() && !judgeModel.isBlank();
    }
}
