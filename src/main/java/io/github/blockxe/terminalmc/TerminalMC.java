package io.github.blockxe.terminalmc;

import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class TerminalMC extends JavaPlugin {

    private final Object lock = new Object();
    private final StringBuilder pending = new StringBuilder();
    private volatile boolean running;
    private volatile SocketChannel channel;
    private Thread receiver;
    private final VtStripper stripper = new VtStripper();

    @Override public void onEnable() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            getLogger().severe("LOCALAPPDATA is missing: this PoC requires native Windows Java.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Path socket = Path.of(local, "mcterm-poc.sock");
        running = true;
        receiver = new Thread(() -> receiveLoop(socket), "McTerm-IPC");
        receiver.setDaemon(true);
        receiver.start();

        // Chat is deliberately rate-limited: raw TUI refreshes can be thousands of updates/s.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            String sample;
            synchronized (lock) {
                if (pending.isEmpty()) return;
                sample = pending.toString();
                pending.setLength(0);
            }
            // Bukkit/Paper chat access stays on the server thread.
            Component message = Component.text("[TTY] " + sample);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
        }, 20L, 20L);
        getLogger().info("Connecting to " + socket);
    }

    private void receiveLoop(Path path) {
        while (running) {
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel = ch;
                ch.connect(UnixDomainSocketAddress.of(path));
                getLogger().info("Connected to native daemon");
                try (InputStreamReader reader = new InputStreamReader(
                        Channels.newInputStream(ch), StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int n;
                    while (running && (n = reader.read(buf)) != -1) {
                        synchronized (lock) {
                            for (int i = 0; i < n; i++) {
                                stripper.accept(buf[i], pending);
                                // Keep memory/chat output bounded between broadcasts.
                                if (pending.length() > 220) pending.setLength(220);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (running) getLogger().warning("Socket unavailable: " + e.getMessage());
            } finally {
                channel = null;
            }
            if (running) {
                try { Thread.sleep(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    @Override public void onDisable() {
        running = false;
        SocketChannel ch = channel;
        if (ch != null) {
            try { ch.close(); } catch (Exception ignored) { }
        }
        if (receiver != null) receiver.interrupt();
    }

    // Minimal streaming ESC/CSI/OSC filter, NOT a terminal emulator.
    private static final class VtStripper {
        private enum State { NORMAL, ESC, CSI, OSC, OSC_ESC }
        private State state = State.NORMAL;

        void accept(char c, StringBuilder out) {
            switch (state) {
                case NORMAL -> {
                    if (c == 0x1b) state = State.ESC;
                    else if (c == '\r' || c == '\n') append(out, ' ');
                    else if (c == '\t') append(out, ' ');
                    else if (!Character.isISOControl(c)) append(out, c);
                }
                case ESC -> {
                    if (c == '[') state = State.CSI;
                    else if (c == ']') state = State.OSC;
                    else state = State.NORMAL;
                }
                case CSI -> {
                    if (c >= 0x40 && c <= 0x7e) state = State.NORMAL;
                }
                case OSC -> {
                    if (c == 0x07) state = State.NORMAL;
                    else if (c == 0x1b) state = State.OSC_ESC;
                }
                case OSC_ESC -> state = (c == '\\') ? State.NORMAL : State.OSC;
            }
        }
        private static void append(StringBuilder out, char c) {
            if (out.length() < 220) out.append(c);
        }
    }
}
