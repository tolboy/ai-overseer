package dev.oseer;

import dev.oseer.cli.Cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Main {
    public static final String VERSION = "0.3.0";

    private Main() {}

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int exitCode = new Cli().run(args);
        if (exitCode != 0) System.exit(exitCode);
    }
}
