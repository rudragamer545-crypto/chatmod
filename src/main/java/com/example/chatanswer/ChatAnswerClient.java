package com.example.chatanswer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
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
    private static List<String> dictionary = null;

    // A line made only of numbers, operators, brackets and spaces
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

        if (lower.contains("fill in the gaps") || lower.contains("math equation")) {
            armedUntil = System.currentTimeMillis() + 10_000;
        }
        if (System.currentTimeMillis() > armedUntil) return;

        for (String line : text.split("\\R")) {
            if (line.contains(":") ) continue; // player chat

            String answer = null;

            // Math: drop the arrow / symbols before the first digit or bracket
            String m = line.replaceFirst("^[^0-9(]+", "").trim();
            if (!m.isEmpty() && m.matches(".*\\d.*") && MATH_LINE.matcher(m).matches()
                    && m.matches(".*[+\\-*/xX×÷−–—✕·].*")) {
                answer = solveMath(m);
            }

            // Fill in the gaps
            if (answer == null) {
                String l = line.replaceFirst("^[^A-Za-z_]+", "").trim();
                if (l.contains("_")) answer = solveGaps(l);
            }

            if (answer != null) {
                final String a = answer;
                armedUntil = 0;
                long delay = 1200 + ThreadLocalRandom.current().nextInt(1200);
                SCHEDULER.schedule(() -> send(a), delay, TimeUnit.MILLISECONDS);
                return;
            }
        }
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

    /** Proper operator precedence: * and / before + and -. */
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

    private List<String> getDictionary() {
        if (dictionary == null) {
            Set<String> names = new LinkedHashSet<>();
            BuiltInRegistries.ITEM.forEach(i -> names.add(name(i.getDescriptionId())));
            BuiltInRegistries.BLOCK.forEach(b -> names.add(name(b.getDescriptionId())));
            BuiltInRegistries.ENTITY_TYPE.forEach(e -> names.add(name(e.getDescriptionId())));
            BuiltInRegistries.MOB_EFFECT.forEach(e -> names.add(name(e.getDescriptionId())));
            names.addAll(List.of("Coinshop", "Vote Party", "Lifesteal"));
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
