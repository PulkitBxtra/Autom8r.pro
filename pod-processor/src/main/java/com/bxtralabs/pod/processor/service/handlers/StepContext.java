package com.bxtralabs.pod.processor.service.handlers;

// About the step run a handler is executing, for not doing things twice:
//   stepRunId        the same for every attempt of this step in this run: an idempotency key
//   attempt          1 for the first try
//   firstStartedAt   when the first attempt started (epoch ms); anything an earlier attempt
//                    created is newer than this
//   mayHaveHappened  an earlier attempt may have done the work without us learning so, so look
//                    for its result before doing it again
public record StepContext(String stepRunId, int attempt, long firstStartedAt, boolean mayHaveHappened) {

    // Hidden in what a step writes (e.g. an HTML comment in a GitHub issue) so a later attempt
    // can recognise its own earlier work.
    public String marker() {
        return "autom8r-step:" + stepRunId;
    }
}
