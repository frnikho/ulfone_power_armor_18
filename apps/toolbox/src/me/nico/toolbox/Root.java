package me.nico.toolbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Runs shell commands through Magisk su. Must be called off the main thread. */
final class Root {
    private static final long TIMEOUT_SECONDS = 20;

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private Root() {}

    static Result run(String command) {
        try {
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "timeout");
            }
            return new Result(process.exitValue(), out.toString().trim());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        }
    }
}
