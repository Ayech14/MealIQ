package io.github.ayech14.mealiq.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Short-term memory of the agent's earlier decisions, per profile, so follow-up requests
 * can refer to them. It lives in memory only and keeps the newest {@code maxEntries}.
 */
public class ConversationMemory {

    static final int RECENT_FALLBACK = 3;
    private static final int MIN_KEYWORD_LENGTH = 4;

    private final int maxEntries;
    private final Map<UUID, Deque<String>> entries = new HashMap<>();

    public ConversationMemory(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized void recordDecision(UUID profileId, String decision) {
        Deque<String> decisions = entries.computeIfAbsent(profileId, id -> new ArrayDeque<>());
        decisions.addFirst(decision);
        while (decisions.size() > maxEntries) {
            decisions.removeLast();
        }
    }

    /**
     * Decisions sharing a keyword with the query, newest first; if none do, the most
     * recent few, since a follow-up often refers to the last answer.
     */
    public synchronized List<String> retrieveRelevantContext(UUID profileId, String query) {
        List<String> all = new ArrayList<>(entries.getOrDefault(profileId, new ArrayDeque<>()));
        List<String> keywords = query == null ? List.of() : List.of(query.toLowerCase(Locale.ROOT).split("\\W+"))
                .stream().filter(w -> w.length() >= MIN_KEYWORD_LENGTH).toList();
        List<String> relevant = all.stream()
                .filter(d -> keywords.stream().anyMatch(d.toLowerCase(Locale.ROOT)::contains)).toList();
        return relevant.isEmpty() ? all.stream().limit(RECENT_FALLBACK).toList() : relevant;
    }
}
