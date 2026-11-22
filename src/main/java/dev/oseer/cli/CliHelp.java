package dev.oseer.cli;

import dev.oseer.BundledResources;

final class CliHelp {
    private CliHelp() {}

    static void print() {
        printResource("help.txt");
    }

    static void printRun() {
        printResource("run-help.txt");
    }

    private static void printResource(String filename) {
        System.out.println(BundledResources.readText("/dev/oseer/cli/" + filename).stripTrailing());
    }
}
