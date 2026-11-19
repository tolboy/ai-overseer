package dev.oseer;

import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** `oseer run --help` prints usage and leaves no run record behind. */
public final class RunHelpTest implements Suite {
    @Override public String name() { return "run-help"; }

    @Override public void run(Check check) throws Exception {
        for (String flag : List.of("--help", "-h")) {
            Path project = Files.createTempDirectory("oseer-run-help");
            Path home = Files.createTempDirectory("oseer-run-help-home");
            List<String> command = new ArrayList<>();
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
            command.add("-cp");
            command.add(classPath());
            command.add("dev.oseer.Main");
            command.add("run");
            command.add(flag);
            ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
            builder.environment().put("OSEER_CONFIG_HOME", home.toString());
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.getErrorStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
            check.eq("run " + flag + " exits 0", 0, process.exitValue());
            check.contains("run " + flag + " prints usage", stdout, "usage: oseer run");
            check.that("run " + flag + " creates no run record", !Files.exists(project.resolve(".oseer").resolve("runs")));
        }
    }

    private static String classPath() {
        List<String> absolute = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            absolute.add(Path.of(entry).toAbsolutePath().toString());
        }
        return String.join(java.io.File.pathSeparator, absolute);
    }
}
