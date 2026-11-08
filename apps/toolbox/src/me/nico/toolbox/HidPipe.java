package me.nico.toolbox;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root pipe to one /dev/hidgN node. busybox dd with iflag=fullblock turns the byte stream
 * into exactly one write per report: f_hid truncates larger writes to report_length.
 */
final class HidPipe {
    static final String SCRIPT = "/data/adb/ventoy/usbhid.sh";
    private static final String DD = "/data/adb/magisk/busybox dd iflag=fullblock bs=";
    private static final long REPORT_GAP_MS = 2;

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final int reportSize;
    private Process process;
    private volatile OutputStream out;

    HidPipe(int reportSize) {
        this.reportSize = reportSize;
    }

    /** Opens the pipe to the node printed by "usbhid.sh <nodeCommand>". Off the main thread. */
    boolean open(String nodeCommand) {
        Root.Result node = Root.run(SCRIPT + " " + nodeCommand);
        if (!node.ok() || !node.output.startsWith("/dev/hidg")) {
            return false;
        }
        try {
            process = new ProcessBuilder("su", "-c",
                    "exec " + DD + reportSize + " of=" + node.output + " 2>/dev/null").start();
            out = process.getOutputStream();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Queues one report (must be reportSize bytes). */
    void send(byte[] report) {
        writer.execute(() -> write(report));
    }

    private void write(byte[] report) {
        OutputStream o = out;
        if (o == null) {
            return;
        }
        try {
            o.write(report);
            o.flush();
            Thread.sleep(REPORT_GAP_MS); // host polls every 1 ms: keep reports distinct
        } catch (IOException e) {
            out = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void close() {
        writer.execute(() -> {
            OutputStream o = out;
            out = null;
            try {
                if (o != null) {
                    o.close();
                }
            } catch (IOException ignored) {
                // Process is going away anyway.
            }
            if (process != null) {
                process.destroy();
            }
        });
        writer.shutdown();
    }
}
