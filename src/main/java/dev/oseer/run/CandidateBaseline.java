package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.OverseerTree;
import dev.oseer.git.GitRepository;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The Git base and contract pinned before any stage was allowed to write. */
record CandidateBaseline(GitRepository repository, String diffBaseCommit,
                         Map<String, String> contractSnapshot) {
    boolean hasSourceChanges() {
        try {
            return !OverseerTree.sourcePaths(repository.changedPaths(diffBaseCommit)).isEmpty();
        } catch (Exception unreadable) {
            // An unreadable tree cannot establish that there are no prior writers.
            return true;
        }
    }

    List<String> changedSourcePaths() throws IOException, InterruptedException {
        return repository.changedPaths(diffBaseCommit).stream()
                .filter(path -> !path.startsWith(OverseerTree.DIRECTORY + "/"))
                .sorted().toList();
    }

    String fingerprint() {
        try {
            return repository.sourceFingerprint(diffBaseCommit);
        } catch (Exception unreadable) {
            // Keep the verdict, but prevent reusing it without proof of which tree it judged.
            return null;
        }
    }

    void requireUnchangedContract(ConfigLoader.Loaded loaded) {
        if (!ContractWatch.contractMatches(loaded, contractSnapshot)) {
            throw new StopException("contract_mutated");
        }
    }
}
