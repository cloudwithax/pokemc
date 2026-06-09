package dev.clxud.poke.util;

import java.util.regex.Pattern;

/**
 * Final-pass scrubber for anything Clanker says. Even though the model is told
 * to speak plain text, models slip in markdown, emoji and exotic symbols — this
 * strips them so the message renders cleanly in vanilla Minecraft chat. The
 * coloured "Clanker »" prefix is added separately and is never run through here.
 */
public final class ChatSanitizer {

    private ChatSanitizer() {
    }

    private static final Pattern CODE_FENCE = Pattern.compile("```[a-zA-Z0-9]*\\n?");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDER = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern ITALIC_UNDER = Pattern.compile("(?<![A-Za-z0-9])_(.+?)_(?![A-Za-z0-9])");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?m)^\\s{0,3}>\\s?");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s{0,3}[-*+]\\s+");
    private static final Pattern SECTION_CODE = Pattern.compile("\\u00A7.");

    public static String clean(String input) {
        if (input == null) {
            return "";
        }
        String s = input;

        // --- strip markdown ---
        s = CODE_FENCE.matcher(s).replaceAll("");
        s = s.replace("`", "");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = BOLD_STAR.matcher(s).replaceAll("$1");
        s = BOLD_UNDER.matcher(s).replaceAll("$1");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = ITALIC_STAR.matcher(s).replaceAll("$1");
        s = ITALIC_UNDER.matcher(s).replaceAll("$1");
        s = HEADING.matcher(s).replaceAll("");
        s = BLOCKQUOTE.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("- ");

        // --- normalise unicode punctuation to ASCII Minecraft can render ---
        s = s.replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-');
        s = s.replace("…", "...");

        // --- strip Minecraft section/colour codes so output stays plain text ---
        s = SECTION_CODE.matcher(s).replaceAll("");
        s = s.replace("§", "");

        // --- drop emoji, pictographs, dingbats, arrows, variation selectors ---
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            if (isRenderable(cp)) {
                out.appendCodePoint(cp);
            }
            i += width;
        }

        // --- collapse to a single clean line ---
        return out.toString()
                .replaceAll("[\\t\\f\\r]+", " ")
                .replaceAll("\\s*\\n\\s*", " ")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    private static boolean isRenderable(int cp) {
        if (cp == '\n') {
            return true; // collapsed to a space later
        }
        if (cp < 0x20 || cp == 0x7F) {
            return false; // control chars
        }
        if (cp <= 0x024F) {
            return true; // ASCII + Latin-1 + Latin Extended-A/B
        }
        if (cp >= 0x2000 && cp <= 0x206F) {
            return false; // general punctuation (smart quotes/dashes already normalised)
        }
        if (cp >= 0x2190 && cp <= 0x2BFF) {
            return false; // arrows, math, misc symbols, dingbats
        }
        if (cp >= 0x2300 && cp <= 0x23FF) {
            return false; // misc technical (⏰ ⚙ etc.)
        }
        if (cp >= 0xFE00 && cp <= 0xFE0F) {
            return false; // variation selectors
        }
        if (cp == 0x200D || cp == 0x20E3) {
            return false; // zero-width joiner, combining keycap
        }
        if (cp >= 0xE000 && cp <= 0xF8FF) {
            return false; // private use area
        }
        if (cp >= 0x1F000) {
            return false; // supplementary-plane emoji & pictographs
        }
        return true;
    }
}
