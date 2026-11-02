import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The demo project's entire definition of "done".
 *
 * Run as a single-file source program (`java tools/Verify.java`), which needs no build step
 * and behaves identically under cmd.exe and /bin/sh — the two shells Overseer runs a check
 * command through. A shell one-liner would have needed two spellings, and a project whose
 * contract differs per platform cannot be gated by one contract.
 *
 * Exit 0 means the acceptance command passed. Anything else is a failed gate, and Overseer
 * records the exit code and both streams as evidence.
 */
public final class Verify {
    public static void main(String[] args) throws Exception {
        Path result = Path.of("result.txt");
        if (!Files.isRegularFile(result)) {
            System.err.println("FAIL: result.txt does not exist");
            System.exit(1);
        }
        String content = Files.readString(result).strip();
        if (!content.equals("OSEER_OK")) {
            System.err.println("FAIL: result.txt should contain exactly OSEER_OK, found: " + content);
            System.exit(1);
        }
        System.out.println("PASS: result.txt contains OSEER_OK");
    }
}
