package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.OverseerTree;

import java.util.List;
import java.util.Map;

/** Whether the hashed contract is still the one this run started against. */
final class ContractWatch {
    private ContractWatch() {}

    /**
     * Which files under `.oseer` moved, for the person who has to put them back.
     *
     * The stop used to say only that "a file under .oseer changed". Measured 2026-10-01: the
     * planner had compiled the task contract into the worktree, the implementer ran a
     * `git checkout` that restored the committed version, and the operator had to diff the
     * tree by hand to learn that one task file, and nothing the agent wrote, was the cause.
     */
    static void recordContractChanges(ConfigLoader.Loaded loaded, Map<String, String> pinned,
                                              Map<String, Object> summary) {
        try {
            List<String> changed = OverseerTree.changedSince(loaded.root(), pinned);
            if (!changed.isEmpty()) summary.put("contract_changes", changed);
        } catch (Exception unreadable) {
            // The stop stands without the list; the reason is already recorded.
        }
    }

    static boolean contractMatches(ConfigLoader.Loaded loaded, Map<String, String> pinned) {
        try {
            return OverseerTree.changedSince(loaded.root(), pinned).isEmpty();
        } catch (Exception missingOrUnreadable) {
            return false;
        }
    }

}
