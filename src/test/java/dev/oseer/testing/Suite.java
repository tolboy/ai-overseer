package dev.oseer.testing;

public interface Suite {
    String name();
    void run(Check check) throws Exception;
}
