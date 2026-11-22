package dev.oseer.run;

/**
 * Raised by a stage that has run out of options. It carries the reason an operator greps
 * for, and the only thing the caller does with it is stop at the human boundary —
 * threading a "should I stop now" flag through every nested loop is how one of the
 * branches quietly forgets to check it.
 */
@SuppressWarnings("serial")
final class StopException extends RuntimeException {
    private final String reason;

    StopException(String reason) {
        super(reason);
        this.reason = reason;
    }

    String reason() { return reason; }
}
