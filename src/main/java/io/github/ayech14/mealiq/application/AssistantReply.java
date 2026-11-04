package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.AssistantRequest;
import java.util.List;
import java.util.UUID;

/**
 * The assistant's answer to one message: what it understood, a reply, and the result of the
 * action it ran (null when it only replied). A pantry or profile update is not applied yet:
 * it waits in {@code pending} until the user confirms it.
 */
public record AssistantReply(AssistantRequest.Action action, String text, AgentResponse result,
                             PendingUpdate pending) {

    /** A pantry or profile update to confirm, described line by line. */
    public record PendingUpdate(UUID id, String title, List<String> lines) {

        public PendingUpdate {
            lines = List.copyOf(lines);
        }
    }

    public AssistantReply {
        text = text == null ? "" : text;
    }
}
