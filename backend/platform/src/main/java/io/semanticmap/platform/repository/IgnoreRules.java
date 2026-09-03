package io.semanticmap.platform.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class IgnoreRules {
    private static final Set<String> EXCLUDED = Set.of(
            ".git",
            ".hg",
            ".svn",
            "node_modules",
            "target",
            "build",
            "dist",
            ".gradle",
            ".m2",
            ".idea",
            ".next",
            ".nuxt",
            ".venv",
            "venv",
            "__pycache__",
            ".cache",
            "coverage");
    private final List<Rule> rules = new ArrayList<>();

    private record Rule(Pattern pattern, boolean include) {}

    public IgnoreRules(String content) {
        for (String line : content.split("\\R")) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) continue;
            boolean include = value.startsWith("!");
            if (include) value = value.substring(1);
            if (value.isEmpty()) continue;
            boolean anchored = value.startsWith("/");
            if (anchored) value = value.substring(1);
            if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            StringBuilder regex = new StringBuilder(anchored || value.contains("/") ? "^" : "^(?:.*/)?");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '*' && i + 1 < value.length() && value.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < value.length() && value.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else regex.append(".*");
                } else if (c == '*') regex.append("[^/]*");
                else if (c == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            rules.add(new Rule(Pattern.compile(regex + "(?:/.*)?$"), include));
        }
    }

    public static IgnoreRules load(Path root) throws IOException {
        StringBuilder patterns = new StringBuilder();
        for (String name : List.of(".gitignore", ".semanticmapignore")) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(file) > 65536) throw new IOException("IGNORE_FILE_TOO_LARGE");
                patterns.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return new IgnoreRules(patterns.toString());
    }

    public boolean alwaysExcluded(String path) {
        for (String part : path.replace('\\', '/').split("/")) {
            if (EXCLUDED.contains(part)
                    || part.equals(".env")
                    || part.startsWith(".env.")
                    || part.endsWith(".pem")
                    || part.endsWith(".key")
                    || part.equals("id_rsa")
                    || part.equals("id_ed25519")) return true;
        }
        return false;
    }

    public boolean ignored(String path) {
        String normalized = path.replace('\\', '/');
        if (alwaysExcluded(normalized)) return true;
        boolean ignored = false;
        for (Rule rule : rules) if (rule.pattern().matcher(normalized).matches()) ignored = !rule.include();
        return ignored;
    }
}
