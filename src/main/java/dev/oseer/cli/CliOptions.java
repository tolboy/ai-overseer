package dev.oseer.cli;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class CliOptions {
    static final int DEFAULT_DECISION_WAIT_MINUTES = 60;
    private static final long MAX_OPERATOR_WAIT_MINUTES = TimeUnit.DAYS.toMinutes(1);

    private CliOptions() {}

    static boolean hasFlag(String[] args, String name) {
        for (String argument : args) if (argument.equals(name)) return true;
        return false;
    }

    static String option(String[] args, String name, String fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) return args[index + 1];
        }
        return fallback;
    }

    /** {@code 1..1440} minutes, or null when the flag is absent. */
    static Integer optionalMinutes(String[] args, String flag) {
        String raw = option(args, flag, null);
        if (raw == null) return null;
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(flag + " must be an integer 1.." + MAX_OPERATOR_WAIT_MINUTES);
        }
        if (minutes < 1 || minutes > MAX_OPERATOR_WAIT_MINUTES) {
            throw new IllegalArgumentException(flag + " must be 1.." + MAX_OPERATOR_WAIT_MINUTES + " minutes");
        }
        return minutes;
    }

    static String defaultRunId() {
        return "run-" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
