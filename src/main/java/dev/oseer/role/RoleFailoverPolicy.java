package dev.oseer.role;

import dev.oseer.config.Policy;
import dev.oseer.config.Profile;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.RoleExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects successors after quota, a failed dispatch, or an incomplete artifact. */
final class RoleFailoverPolicy {
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?i)retry[- ]after:?\\s*(\\d{1,4})\\s*(s|sec|seconds?)?\\b");
    private static final Duration MAX_RATE_LIMIT_PAUSE = Duration.ofMinutes(15);
    private static final Set<String> TRANSIENT_API_FAILURES = Set.of(
            "role_api_network_error", "role_api_timeout", "role_api_rate_limited",
            "role_api_provider_error", "role_api_output_truncated");

    record Stage(String role, String name, RoleResolver.Writers writers, RunOverride overlay) {}

    /** Shared views of the controller's exclusions, which grow as dispatches fail. */
    record Exclusions(Set<String> exhausted, Set<String> failed, Set<String> unavailable) {
        boolean excludes(Profile profile) {
            String name = profile.name();
            return exhausted.contains(name) || failed.contains(name) || unavailable.contains(name);
        }
    }

    record BlockedSwitch(String rule, Profile from, Profile to, String detail) {}

    record Successor(Profile profile, Map<String, String> rejected, List<BlockedSwitch> blocked) {}

    private final UserConfig user;
    private final Stage stage;
    private final Exclusions exclusions;
    private final RoleResolver.Availability availability;

    RoleFailoverPolicy(UserConfig user, Stage stage, Exclusions exclusions,
                       RoleResolver.Availability availability) {
        this.user = user;
        this.stage = stage;
        this.exclusions = exclusions;
        this.availability = availability;
    }

    Successor next(Profile from, long rotation, boolean apiFallbackActive) {
        return apiFallbackActive ? seekApiSuccessor(from) : seekRosterSuccessor(from, rotation);
    }

    /**
     * Quota and availability exclusions also apply to the effective profile in a roster
     * search, since a host overlay may have selected a sibling profile with its own name.
     */
    private Successor seekRosterSuccessor(Profile from, long rotation) {
        List<BlockedSwitch> blocked = new ArrayList<>();
        try {
            List<RoleResolver.Candidate> verdicts = candidates(profile -> {
                Profile effective = adaptForDispatch(profile);
                return effective != null && !exclusions.excludes(effective) && availability.available(effective);
            });
            Policy.RoleSpec spec = user.policy().roles().get(stage.role());
            List<Profile> eligible = new ArrayList<>();
            Map<String, String> rejected = new LinkedHashMap<>();
            for (RoleResolver.Candidate verdict : verdicts) {
                if (!verdict.eligible()) {
                    rejected.put(verdict.profile(), verdict.rejected());
                    continue;
                }
                Profile effective = adapt(user.profiles().get(verdict.profile()));
                if (admitsFamily(user.policy(), from, effective, blocked)) eligible.add(effective);
            }
            if (eligible.isEmpty() || spec == null) return new Successor(null, rejected, blocked);
            List<Profile> starters = eligible.stream().filter(item -> !spec.standby(item.name())).toList();
            if (!starters.isEmpty()) eligible = starters;
            int index = "first".equals(spec.strategy()) ? 0 : Math.floorMod(rotation, eligible.size());
            return new Successor(eligible.get(index), rejected, blocked);
        } catch (RuntimeException none) {
            return new Successor(null, Map.of(), blocked);
        }
    }

    /** Apply the ordinary admission rules to the configured API fallback order. */
    private Successor seekApiSuccessor(Profile from) {
        List<BlockedSwitch> blocked = new ArrayList<>();
        List<RoleResolver.Candidate> verdicts = candidates(profile -> {
            Profile effective = adaptForDispatch(profile);
            return effective != null && availability.available(effective);
        });
        Map<String, RoleResolver.Candidate> byName = new LinkedHashMap<>();
        verdicts.forEach(candidate -> byName.put(candidate.profile(), candidate));
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String name : user.policy().roles().get(stage.role()).apiFallbackProfiles()) {
            RoleResolver.Candidate candidate = byName.get(name);
            if (candidate == null) {
                rejected.put(name, "profile_not_in_roster");
            } else if (!candidate.eligible()) {
                rejected.put(name, candidate.rejected());
            } else {
                Profile chosen = adapt(user.profiles().get(name));
                if (admitsFamily(user.policy(), from, chosen, blocked)) {
                    return new Successor(chosen, rejected, blocked);
                }
            }
        }
        return new Successor(null, rejected, blocked);
    }

    private List<RoleResolver.Candidate> candidates(RoleResolver.Availability admission) {
        return new RoleResolver().candidates(stage.role(), user.policy(), user.profiles(), stage.writers(),
                admission, exclusions.exhausted(), exclusions.failed(), exclusions.unavailable());
    }

    private Profile adaptForDispatch(Profile profile) {
        return stage.overlay().problem(stage.name(), profile, user.profiles()) == null ? adapt(profile) : null;
    }

    private Profile adapt(Profile profile) {
        return stage.overlay().adapt(stage.name(), profile, user.profiles());
    }

    static boolean canFailOver(RoleExecutor.Result result, Profile profile, boolean interrupted) {
        if (result.ok()) return false;
        if (apiSideFailure(result.code(), profile)
                && !interrupted
                && !"cancelled".equals(result.evidence().get("api_retry_stopped"))
                && !"cancel".equals(result.evidence().get("failure_phase"))) return true;
        return vendorFailure(result.code()) && !"api".equals(profile.runner()) && profile.harness() == null;
    }

    static boolean missingArtifact(String code) {
        return vendorFailure(code) || transientApiFailure(code);
    }

    /**
     * A grok/codex/claude subscription CLI must not be moved onto a paid API serving that
     * same family. Cross-family (grok CLI to a DeepSeek API) is a different rule: the pin.
     */
    private static boolean admitsFamily(Policy policy, Profile from, Profile to,
                                        List<BlockedSwitch> blocked) {
        if (from == null || to == null || policy == null) return true;
        if (!"never".equals(policy.subscriptionToSameFamilyApi())) return true;
        if (!subscriptionCli(from) || !paidApi(to) || !sameFamily(from, to)) return true;
        blocked.add(new BlockedSwitch("subscription_to_same_family_api", from, to,
                "a subscription CLI is not failed over onto a paid API of the same model family; "
                        + "set failover.subscription_to_same_family_api: allow to permit it"));
        return false;
    }

    private static boolean subscriptionCli(Profile profile) {
        if (profile.harness() != null) return false;
        if (!"direct".equals(profile.runner()) && !"orca".equals(profile.runner())) return false;
        String family = familyOf(profile);
        return "x-ai".equals(family) || "openai".equals(family) || "anthropic".equals(family);
    }

    private static boolean paidApi(Profile profile) {
        return "api".equals(profile.runner()) || profile.harness() != null;
    }

    private static String familyOf(Profile profile) {
        String vendor = Profile.canonicalVendor(profile.vendor());
        String modelVendor = Profile.canonicalVendor(profile.modelVendor());
        for (String token : new String[] {vendor, modelVendor}) {
            if ("x-ai".equals(token) || "openai".equals(token) || "anthropic".equals(token)) return token;
        }
        String model = profile.model() == null ? "" : profile.model().toLowerCase(Locale.ROOT);
        if (model.startsWith("x-ai/") || model.startsWith("grok")) return "x-ai";
        if (model.startsWith("openai/") || model.startsWith("gpt-")) return "openai";
        if (model.startsWith("anthropic/") || model.startsWith("claude")) return "anthropic";
        return null;
    }

    private static boolean sameFamily(Profile left, Profile right) {
        String one = familyOf(left);
        return one != null && one.equals(familyOf(right));
    }

    // Auth, identity, parse, cancellation and admission/configuration errors are
    // deliberately absent. A different provider cannot repair those safely. Truncation is
    // here: the answer hit this profile's output cap, so nothing was judged, and a declared
    // fallback with its own cap (or a CLI reviewer) can produce the whole artifact. Measured
    // 2026-11-16 on hello-loop: a GLM review stopped at 4096 output tokens and went straight
    // to a person although codex-review was configured as the next fallback.
    private static boolean transientApiFailure(String code) {
        return code != null && TRANSIENT_API_FAILURES.contains(code);
    }

    /**
     * An API stall takes the API fallback list. The same code from a direct CLI takes the
     * roster, because a CLI has no {@code api_fallback_profiles}. Putting the code in
     * {@link #transientApiFailure} would send the CLI down the API list.
     */
    static boolean apiSideFailure(String code, Profile profile) {
        return transientApiFailure(code)
                || ("role_stalled".equals(code) && "api".equals(profile.runner()));
    }

    /**
     * Codes that mean the vendor produced nothing a later stage can believe.
     *
     * A timeout that already wrote a valid artifact does not arrive here: the executor
     * returns ok and leaves {@code timed_out} on the attempt. {@code role_timeout} stays
     * in this set for the call that left no complete artifact.
     */
    private static final Set<String> VENDOR_FAILURE_CODES = Set.of(
            "role_command_failed",
            "role_timeout",
            "role_artifact_unparseable",
            "role_artifact_incomplete",
            "role_artifact_schema_violation",
            "role_local_endpoint_unreachable",
            "role_local_http_error",
            "role_vendor_unavailable",
            "role_stalled");

    private static boolean vendorFailure(String code) {
        // Harness admission/configuration/identity failures describe the execution boundary.
        // Paying another writer cannot repair it and may obscure edits from earlier turns.
        if (code == null || code.startsWith("role_harness_")
                || "harness_coding_unproven".equals(code)) return false;
        return VENDOR_FAILURE_CODES.contains(code);
    }

    /**
     * The ledger {@code cause} for a missing artifact. The role's own code stays on
     * {@code role_code}, so a network failure and a schema failure are not collapsed
     * into one token where the evidence is read.
     */
    static String vendorFailureCause(String code) {
        return switch (code) {
            case "role_command_failed", "role_local_endpoint_unreachable",
                    "role_local_http_error", "role_api_network_error",
                    "role_api_provider_error", "role_api_rate_limited" -> "vendor_call_failed";
            case "role_timeout", "role_api_timeout" -> "role_timeout";
            case "role_stalled" -> "role_stalled";
            case "role_artifact_unparseable", "role_artifact_incomplete",
                    "role_artifact_schema_violation", "role_api_output_truncated" -> "artifact_invalid";
            case "role_vendor_unavailable" -> "vendor_unavailable";
            default -> code;
        };
    }

    /** A missing artifact, as opposed to a spent subscription. The bound counts only these. */
    static boolean countsAsVendorFailover(Object cause) {
        String value = cause == null ? "" : String.valueOf(cause);
        return "vendor_call_failed".equals(value)
                || "role_timeout".equals(value)
                || "role_stalled".equals(value)
                || "artifact_invalid".equals(value)
                || "vendor_unavailable".equals(value);
    }

    /**
     * How long to pause before a rate-limit retry: the seconds the vendor asked for when its
     * message carried a plain `retry after N seconds` or `Retry-After: N`, else the policy's
     * backoff doubled per attempt. Bounded to fifteen minutes either way, and a time of day the
     * vendor printed without a date is never parsed into an instant.
     */
    static long retryPauseMillis(Map<String, Object> evidence, Policy.RateLimitRetry retry, int attempt) {
        long seconds = -1;
        if (evidence != null && evidence.get("quota") instanceof Map<?, ?> quota
                && quota.get("vendor_message") instanceof String message) {
            Matcher named = RETRY_AFTER.matcher(message);
            if (named.find()) seconds = Long.parseLong(named.group(1));
        }
        if (seconds < 0) seconds = retry.backoffSeconds() * (1L << Math.max(0, attempt - 1));
        return Math.min(seconds, MAX_RATE_LIMIT_PAUSE.toSeconds()) * 1000L;
    }
}
