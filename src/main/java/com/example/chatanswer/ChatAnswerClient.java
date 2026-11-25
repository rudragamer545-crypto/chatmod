package com.example.chatanswer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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

    private static final Pattern MATH_LINE = Pattern.compile("^[0-9+\\-*/xX×÷().\\s−–—✕·]+$");

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) handle(msg);
        });
    }

    private void handle(Component message) {
        String text = message.getString();
        String lower = text.toLowerCase();

        // Only the "The first to ..." header arms the mod (not the winner announcement)
        if (lower.contains("first to")) {
            if (lower.contains("fill in the gaps")) arm("gaps");
            else if (lower.contains("math") || lower.contains("equation")) arm("math");
            else if (lower.contains("unscramble")) arm("unscramble");
            else if (lower.contains("first to type")) arm("type", 4_000);
            else if (lower.contains("first to answer") || lower.contains("the question")) arm("trivia", 6_000);
        }
        if (System.currentTimeMillis() > armedUntil) return;

        for (String line : text.split("\\R")) {
            String ll = line.toLowerCase();
            if (line.contains(":") || ll.contains("first to") || ll.contains("chatgames")
                    || ll.contains("seconds") || ll.contains("defeated") || ll.contains("welcome")) continue;

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
                case "type" -> {
                    java.util.regex.Matcher mt = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*(.+)$").matcher(line.trim());
                    if (mt.matches()) answer = mt.group(1).trim();
                }
                case "trivia" -> {
                    java.util.regex.Matcher mq = Pattern.compile("^[^\\p{L}\\p{N}\\s]+\\s*(.+)$").matcher(line.trim());
                    if (mq.matches() && mq.group(1).contains("?")) {
                        String q = mq.group(1).trim();
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
                SCHEDULER.schedule(() -> send(a), delay, TimeUnit.MILLISECONDS);
                return;
            }
        }
    }

    /** Reads config/chatanswer-delay.txt, one line: "min max" in seconds (e.g. "3 6"). */
    private long getDelayMs() {
        double min = 2.5, max = 4.5;
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("chatanswer-delay.txt");
            if (!Files.exists(file)) {
                Files.writeString(file, "2.5 4.5\n");
            } else {
                String[] parts = Files.readString(file).trim().split("\\s+");
                min = Double.parseDouble(parts[0]);
                max = parts.length > 1 ? Double.parseDouble(parts[1]) : min;
            }
        } catch (Exception ignored) { }
        if (max < min) max = min;
        double sec = min + ThreadLocalRandom.current().nextDouble() * (max - min);
        return (long) (sec * 1000);
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

    // ---------- TRIVIA ----------
    // Format: keyword1,keyword2=answer  (all keywords must appear in the question)
    private static final String[] BUILT_IN_TRIVIA = {
        "rarest,axolotl=blue",
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
