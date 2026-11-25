package com.example.chatanswer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ChatAnswerClient implements ClientModInitializer {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static volatile long armedUntil = 0;
    private static volatile String armedType = "";
    private static List<String> dictionary = null;

    // Recent player chat (name -> last message) and the current trivia question, used for learning
    private static final java.util.Map<String, String> RECENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastQuestion = null;

    // Guess-the-number game: numbers other players already tried (they were wrong)
    private static final java.util.Set<Integer> GUESSED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile long guessUntil = 0;

    private static final Pattern MATH_LINE = Pattern.compile("^[0-9+\\-*/xX×÷().\\s−–—✕·]+$");

    @Override
    public void onInitializeClient() {
        loadSettings();
        loadCf();
        registerCommands();
        registerCfCommands();
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> record(msg.getString()));
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            try { trackCoinflip(msg.getString()); } catch (Exception ignored) { }
            if (!overlay) handle(msg);
        });
    }

    private void handle(Component message) {
        if (!enabled) return;
        String text = message.getString();
        String lower = text.toLowerCase();
        record(text);
        learnFromWinner(text);

        // Only the "The first to ..." header arms the mod (not the winner announcement)
        if (lower.contains("first to")) {
            if (lower.contains("fill in the gaps")) arm("gaps");
            else if (lower.contains("math") || lower.contains("equation")) arm("math");
            else if (lower.contains("unscramble")) arm("unscramble");
            else if (lower.contains("first to type")) arm("type", 4_000);
            else if (lower.contains("reverse")) arm("reverse", 4_000);
            else if (lower.contains("first to guess")) {
                GUESSED.clear();
                guessUntil = System.currentTimeMillis() + 20_000;
                arm("guess", 4_000);
            }
            else if (lower.contains("first to answer") || lower.contains("the question")) arm("trivia", 6_000);
        }
        if (System.currentTimeMillis() > armedUntil) return;

        for (String line : text.split("\\R")) {
            String ll = line.toLowerCase();
            if (line.contains(":") || ll.contains("first to") || ll.contains("chatgames")
                    || ll.contains("seconds") || ll.contains("streak") || ll.contains("defeated") || ll.contains("welcome")) continue;

            String answer = null;
            switch (armedType) {
                case "math" -> {
                    String m = line.replaceFirst("^[^0-9(]+", "").trim();
                    if (!m.isEmpty() && MATH_LINE.matcher(m).matches()
                            && m.matches(".*\\d.*") && m.matches(".*[+\\-*/xX×÷−–—✕·].*")) {
                        answer = solveMath(m);
                    }
                }
                case "gaps" -> {
                    String l = line.replaceFirst("^[^A-Za-z_]+", "").trim();
                    if (l.contains("_")) answer = solveGaps(l);
                }
                case "unscramble" -> {
                    String l = line.replaceFirst("^[^A-Za-z]+", "").trim();
                    if (!l.isEmpty() && l.matches("[A-Za-z ]+")) answer = solveUnscramble(l);
                }
                case "guess" -> {
                    java.util.regex.Matcher mg = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*(\\d+)\\s*[-–—]\\s*(\\d+)$").matcher(line.trim());
                    if (mg.matches()) answer = "GUESS:" + mg.group(1) + ":" + mg.group(2);
                }
                case "reverse" -> {
                    java.util.regex.Matcher mr = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*([A-Za-z0-9]+)$").matcher(line.trim());
                    if (mr.matches()) answer = new StringBuilder(mr.group(1)).reverse().toString();
                }
                case "type" -> {
                    java.util.regex.Matcher mt = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*(.+)$").matcher(line.trim());
                    if (mt.matches()) answer = mt.group(1).trim();
                }
                case "trivia" -> {
                    java.util.regex.Matcher mq = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*(.+)$").matcher(line.trim());
                    if (mq.matches() && mq.group(1).contains("?")) {
                        String q = mq.group(1).trim();
                        lastQuestion = q;
                        answer = solveTrivia(q);
                        if (answer == null) logUnanswered(q);
                    }
                }
                default -> { }
            }

            if (answer != null) {
                final String a = answer;
                armedUntil = 0;
                long delay = getDelayMs();
                SCHEDULER.schedule(() -> send(resolve(a)), delay, TimeUnit.MILLISECONDS);
                return;
            }
        }
    }

    // ---------- SETTINGS (saved in config/chatanswer-delay.txt) ----------
    private static volatile double minDelay = 2.0;
    private static volatile double maxDelay = 3.0;
    private static volatile boolean enabled = true;

    private static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatanswer-delay.txt");
    }

    private static void loadSettings() {
        try {
            Path f = settingsFile();
            if (Files.exists(f)) {
                String[] parts = Files.readString(f).trim().split("\\s+");
                minDelay = Double.parseDouble(parts[0]);
                maxDelay = parts.length > 1 ? Double.parseDouble(parts[1]) : minDelay;
                if (parts.length > 2) enabled = !parts[2].equalsIgnoreCase("off");
            }
        } catch (Exception ignored) { }
        if (maxDelay < minDelay) maxDelay = minDelay;
    }

    private static void saveSettings() {
        try {
            Files.writeString(settingsFile(), minDelay + " " + maxDelay + " " + (enabled ? "on" : "off") + "\n");
        } catch (Exception ignored) { }
    }

    private long getDelayMs() {
        double sec = minDelay + ThreadLocalRandom.current().nextDouble() * (maxDelay - minDelay);
        return (long) (sec * 1000);
    }

    private static void say(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal("[ChatAnswer] " + msg));
    }

    private static int setDelay(CommandContext<FabricClientCommandSource> ctx, double min, double max) {
        if (max < min) { double t = min; min = max; max = t; }
        minDelay = min;
        maxDelay = max;
        saveSettings();
        say(ctx, "Delay set to " + min + " - " + max + " seconds.");
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, (enabled ? "ON" : "OFF") + ", delay " + minDelay + " - " + maxDelay + " seconds.");
        return 1;
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("chatanswer")
                .executes(ctx -> status(ctx))
                .then(ClientCommandManager.literal("status").executes(ctx -> status(ctx)))
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    enabled = true; saveSettings(); say(ctx, "Turned ON."); return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    enabled = false; saveSettings(); say(ctx, "Turned OFF."); return 1;
                }))
                .then(ClientCommandManager.literal("delay")
                    .executes(ctx -> status(ctx))
                    .then(ClientCommandManager.argument("min", DoubleArgumentType.doubleArg(0, 60))
                        .executes(ctx -> {
                            double a = DoubleArgumentType.getDouble(ctx, "min");
                            return setDelay(ctx, a, a);
                        })
                        .then(ClientCommandManager.argument("max", DoubleArgumentType.doubleArg(0, 60))
                            .executes(ctx -> setDelay(ctx,
                                DoubleArgumentType.getDouble(ctx, "min"),
                                DoubleArgumentType.getDouble(ctx, "max")))))))
        );
    }

    /** Turns a "GUESS:min:max" placeholder into a number not yet tried by other players. */
    private static String resolve(String a) {
        if (!a.startsWith("GUESS:")) return a;
        String[] p = a.split(":");
        int min = Integer.parseInt(p[1]), max = Integer.parseInt(p[2]);
        if (max < min) { int t = min; min = max; max = t; }
        List<Integer> open = new ArrayList<>();
        for (int i = min; i <= max; i++) if (!GUESSED.contains(i)) open.add(i);
        if (open.isEmpty()) return String.valueOf(min + ThreadLocalRandom.current().nextInt(max - min + 1));
        return String.valueOf(open.get(ThreadLocalRandom.current().nextInt(open.size())));
    }

    private void arm(String type) { arm(type, 10_000); }

    private void arm(String type, long ms) {
        armedType = type;
        armedUntil = System.currentTimeMillis() + ms;
    }

    // ---------- MATH ----------
    private String solveMath(String expr) {
        try {
            String s = expr.replace('×', '*').replace('x', '*').replace('X', '*')
                    .replace('✕', '*').replace('·', '*').replace('÷', '/')
                    .replace('−', '-').replace('–', '-').replace('—', '-')
                    .replaceAll("\\s+", "");
            double v = new Parser(s).parse();
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            if (v == Math.rint(v)) return String.valueOf((long) v);
            return String.valueOf(Math.round(v * 100.0) / 100.0);
        } catch (Exception e) {
            return null;
        }
    }

    private static class Parser {
        final String s; int pos = 0;
        Parser(String s) { this.s = s; }
        double parse() {
            double v = expr();
            if (pos != s.length()) throw new RuntimeException("junk");
            return v;
        }
        double expr() {
            double v = term();
            while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                char op = s.charAt(pos++);
                double r = term();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }
        double term() {
            double v = factor();
            while (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                char op = s.charAt(pos++);
                double r = factor();
                v = op == '*' ? v * r : v / r;
            }
            return v;
        }
        double factor() {
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -factor(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return factor(); }
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expr();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new RuntimeException("paren");
                pos++;
                return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new RuntimeException("number");
            return Double.parseDouble(s.substring(start, pos));
        }
    }

    // ---------- FILL IN THE GAPS ----------
    private String solveGaps(String pattern) {
        String[] pw = pattern.toLowerCase().split("\\s+");
        for (String candidate : getDictionary()) {
            String[] cw = candidate.toLowerCase().split("\\s+");
            if (cw.length != pw.length) continue;
            boolean ok = true;
            for (int i = 0; i < pw.length && ok; i++) {
                if (pw[i].length() != cw[i].length()) { ok = false; break; }
                for (int j = 0; j < pw[i].length(); j++) {
                    char p = pw[i].charAt(j);
                    if (p != '_' && p != cw[i].charAt(j)) { ok = false; break; }
                }
            }
            if (ok) return candidate;
        }
        return null;
    }

    // ---------- UNSCRAMBLE ----------
    private String solveUnscramble(String scrambled) {
        String key = letterKey(scrambled);
        if (key.isEmpty()) return null;
        for (String candidate : getDictionary()) {
            if (letterKey(candidate).equals(key)) return candidate;
        }
        return null;
    }

    private static String letterKey(String s) {
        char[] c = s.toLowerCase().replaceAll("[^a-z]", "").toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    // ---------- COINFLIP TRACKER ----------
    private static final List<Boolean> CF = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile long cfActiveUntil = 0;
    private static volatile String cfResult = null, cfWinner = null, cfLoser = null;

    private static Path cfFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("coinflip-log.csv");
    }

    private static void loadCf() {
        try {
            Path f = cfFile();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.split(",");
                if (p.length >= 2 && (p[1].equals("W") || p[1].equals("L"))) CF.add(p[1].equals("W"));
            }
        } catch (Exception ignored) { }
    }

    /** Reads the "Coinflip Summary" chat message the server prints after each flip. */
    private static void trackCoinflip(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("coinflip summary")) {
            cfActiveUntil = System.currentTimeMillis() + 6_000;
            cfResult = null; cfWinner = null; cfLoser = null;
        }
        if (System.currentTimeMillis() > cfActiveUntil) return;

        for (String line : text.split("\\R")) {
            String l = line.trim();
            String ll = l.toLowerCase();
            if (ll.contains("you won the coinflip")) cfResult = "W";
            else if (ll.contains("you lost the coinflip")) cfResult = "L";
            int c = l.indexOf(':');
            if (c < 0) continue;
            String key = ll.substring(0, c).trim();
            String val = l.substring(c + 1).trim();
            if (key.endsWith("winner")) cfWinner = val;
            else if (key.endsWith("loser")) cfLoser = val;
            else if (key.endsWith("amount")) { finishCf(val); return; }
        }
    }

    private static void finishCf(String amount) {
        String me = Minecraft.getInstance().getUser().getName();
        String res = cfResult;
        if (res == null) {
            if (me.equalsIgnoreCase(cfWinner)) res = "W";
            else if (me.equalsIgnoreCase(cfLoser)) res = "L";
        }
        cfActiveUntil = 0;
        if (res == null) return;
        String opp = res.equals("W") ? cfLoser : cfWinner;
        CF.add(res.equals("W"));
        try {
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Files.writeString(cfFile(), now + "," + res + "," + amount + "," + opp + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static List<String> cfStats() {
        List<Boolean> r = new ArrayList<>(CF);
        List<String> out = new ArrayList<>();
        int n = r.size();
        if (n == 0) { out.add("No coinflips logged yet."); return out; }

        int w = 0;
        for (boolean b : r) if (b) w++;
        out.add(String.format("Flips: %d | Wins: %d | Losses: %d | Win rate: %.1f%%", n, w, n - w, 100.0 * w / n));

        int bestW = 0, bestL = 0, run = 0;
        Boolean prev = null;
        for (boolean b : r) {
            run = (prev != null && prev == b) ? run + 1 : 1;
            prev = b;
            if (b) bestW = Math.max(bestW, run); else bestL = Math.max(bestL, run);
        }
        out.add("Longest win streak: " + bestW + " | Longest loss streak: " + bestL
                + " | Current: " + run + (prev ? " W" : " L"));

        for (boolean type : new boolean[]{false, true}) {
            for (int k = 1; k <= 3; k++) {
                int cases = 0, wins = 0;
                for (int i = k; i < n; i++) {
                    boolean all = true;
                    for (int j = i - k; j < i; j++) if (r.get(j) != type) { all = false; break; }
                    if (!all) continue;
                    cases++;
                    if (r.get(i)) wins++;
                }
                if (cases > 0) {
                    out.add(String.format("After %d %s in a row: %d cases -> next was a win %d times (%.0f%%)",
                            k, type ? "win(s)" : "loss(es)", cases, wins, 100.0 * wins / cases));
                }
            }
        }
        out.add(n < 100 ? "Only " + n + " flips logged. You need about 100+ before any pattern means anything."
                : "If every number stays near 50%, the results look random.");
        return out;
    }

    private static void registerCfCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("cfstats")
                .executes(ctx -> {
                    for (String line : cfStats()) say(ctx, line);
                    return 1;
                })
                .then(ClientCommandManager.literal("reset").executes(ctx -> {
                    CF.clear();
                    try { Files.deleteIfExists(cfFile()); } catch (Exception ignored) { }
                    say(ctx, "Coinflip log cleared.");
                    return 1;
                })))
        );
    }

    // ---------- TRIVIA ----------
    // Format: keyword1,keyword2=answer  (all keywords must appear in the question)
    private static final String[] BUILT_IN_TRIVIA = {
        "rarest,axolotl=blue",
        "tamed,bones=wolf",
        "ghast,drop=ghast tear",
        "creeper,drop=gunpowder",
        "enderman,drop=ender pearl",
        "blaze,drop=blaze rod",
        "slime,drop=slimeball",
        "wither,drop=nether star",
        "shulker,drop=shulker shell",
        "color,creeper=green",
        "hearts,player,start=10",
    };

    private String solveTrivia(String question) {
        String n = " " + question.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        List<String> entries = new ArrayList<>(Arrays.asList(BUILT_IN_TRIVIA));
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("chatanswer-trivia.txt");
            if (Files.exists(file)) entries.addAll(Files.readAllLines(file));
        } catch (Exception ignored) { }

        for (String e : entries) {
            int eq = e.indexOf('=');
            if (eq <= 0 || e.trim().startsWith("#")) continue;
            String answer = e.substring(eq + 1).trim();
            if (answer.isEmpty()) continue;
            boolean all = true;
            for (String k : e.substring(0, eq).toLowerCase().split(",")) {
                if (!k.isBlank() && !n.contains(k.trim())) { all = false; break; }
            }
            if (all) return answer;
        }
        return null;
    }

    private static void record(String text) {
        for (String line : text.split("\\R")) {
            int c = line.indexOf(": ");
            if (c <= 0) continue;
            String[] pre = line.substring(0, c).trim().split("\\s+");
            String name = pre[pre.length - 1].toLowerCase();
            String msg = line.substring(c + 2).trim();
            if (msg.isEmpty()) continue;
            if (guessUntil > System.currentTimeMillis() && msg.matches("\\d{1,6}")) {
                try { GUESSED.add(Integer.parseInt(msg)); } catch (Exception ignored) { }
            }
            if (RECENT.size() > 300) RECENT.clear();
            RECENT.put(name, msg);
        }
    }

    /** When the server announces the winner, save that player's message as the answer. */
    private void learnFromWinner(String text) {
        String q = lastQuestion;
        if (q == null) return;
        for (String line : text.split("\\R")) {
            int i = line.toLowerCase().indexOf(" answered correctly");
            if (i <= 0) continue;
            String[] pre = line.substring(0, i).trim().split("\\s+");
            String name = pre[pre.length - 1].toLowerCase();
            String ans = RECENT.get(name);
            lastQuestion = null;
            if (ans == null || ans.isBlank()) return;
            if (solveTrivia(q) != null) return; // already known
            String key = q.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
            if (key.isEmpty()) return;
            try {
                Path f = FabricLoader.getInstance().getConfigDir().resolve("chatanswer-trivia.txt");
                Files.writeString(f, key + "=" + ans + "\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) { }
            return;
        }
    }

    private void logUnanswered(String question) {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("chatanswer-unanswered.txt");
            Files.writeString(file, question + "\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    // ---------- DICTIONARY ----------
    private List<String> getDictionary() {
        if (dictionary == null) {
            Set<String> names = new LinkedHashSet<>();
            BuiltInRegistries.ITEM.forEach(i -> names.add(name(i.getDescriptionId())));
            BuiltInRegistries.BLOCK.forEach(b -> names.add(name(b.getDescriptionId())));
            BuiltInRegistries.ENTITY_TYPE.forEach(e -> names.add(name(e.getDescriptionId())));
            BuiltInRegistries.MOB_EFFECT.forEach(e -> names.add(name(e.getDescriptionId())));
            names.addAll(List.of("Coinshop", "Vote Party", "Lifesteal"));

            // Your own extra words: config/chatanswer-words.txt (one word or phrase per line)
            try {
                Path file = FabricLoader.getInstance().getConfigDir().resolve("chatanswer-words.txt");
                if (Files.exists(file)) {
                    for (String w : Files.readAllLines(file)) {
                        if (!w.isBlank()) names.add(w.trim());
                    }
                }
            } catch (Exception ignored) { }

            dictionary = new ArrayList<>(names);
        }
        return dictionary;
    }

    private static String name(String translationKey) {
        return Component.translatable(translationKey).getString();
    }

    private void send(String answer) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.connection.sendChat(answer);
        });
    }
}
