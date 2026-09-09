package io.semanticmap.platform.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SemanticMatcher {

    private static final Map<String, Set<String>> SYNONYMS = new HashMap<>();

    static {
        SYNONYMS.put("видео", Set.of("video", "stream", "camera", "playback", "media"));
        SYNONYMS.put("видеопоток", Set.of("stream", "video", "camera", "hls", "rtsp", "live"));
        SYNONYMS.put("камера", Set.of("camera", "cameras", "cam", "device", "stream"));
        SYNONYMS.put("камеры", Set.of("camera", "cameras", "cam", "device"));
        SYNONYMS.put("стрим", Set.of("stream", "video", "playback", "live"));
        SYNONYMS.put("трансляция", Set.of("stream", "video", "camera", "broadcast"));
        SYNONYMS.put("воспроизведение", Set.of("playback", "play", "replay", "archive", "stream"));
        SYNONYMS.put("запись", Set.of("playback", "record", "archive", "history"));
        SYNONYMS.put("архив", Set.of("playback", "archive", "record", "history"));
        SYNONYMS.put("карта", Set.of("map", "geo", "location", "coordinate"));
        SYNONYMS.put("гео", Set.of("map", "geo", "location", "coordinate"));
        SYNONYMS.put("пользователь", Set.of("user", "users", "account", "profile", "principal"));
        SYNONYMS.put("пользователи", Set.of("user", "users", "account", "profile", "principal"));
        SYNONYMS.put("юзер", Set.of("user", "users", "account", "profile"));
        SYNONYMS.put("права", Set.of("permission", "role", "auth", "access", "security", "privilege"));
        SYNONYMS.put("доступ", Set.of("permission", "role", "auth", "access", "security"));
        SYNONYMS.put("авторизация", Set.of("auth", "login", "token", "security"));
        SYNONYMS.put("токен", Set.of("token", "auth", "jwt", "session"));
        SYNONYMS.put("удаление", Set.of("delete", "remove", "destroy"));
        SYNONYMS.put("удалить", Set.of("delete", "remove", "destroy"));
        SYNONYMS.put("создание", Set.of("create", "post", "add", "new", "register"));
        SYNONYMS.put("создать", Set.of("create", "post", "add", "new", "register"));
        SYNONYMS.put("обновить", Set.of("update", "put", "patch", "edit", "modify"));
        SYNONYMS.put("изменение", Set.of("update", "put", "patch", "edit", "modify"));
        SYNONYMS.put("датасет", Set.of("dataset", "data", "set"));
        SYNONYMS.put("датасеты", Set.of("dataset", "data", "set"));
        SYNONYMS.put("набор", Set.of("dataset", "data", "collection"));
        SYNONYMS.put("ошибка", Set.of("error", "exception", "fail"));
    }

    public static List<Map<String, Object>> rank(List<Map<String, Object>> endpoints, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyList();
        }

        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        String[] words = query.split("[\\s,;.:!?\"'()\\[\\]{}]+");

        List<Map<String, Object>> matched = new ArrayList<>();

        for (Map<String, Object> ep : endpoints) {
            String id = ep.get("id") != null ? ep.get("id").toString() : "";
            String path = ep.get("path") != null ? ep.get("path").toString().toLowerCase(Locale.ROOT) : "";
            String method = ep.get("method") != null ? ep.get("method").toString().toUpperCase(Locale.ROOT) : "";
            String ownerKey = ep.get("ownerKey") != null ? ep.get("ownerKey").toString().toLowerCase(Locale.ROOT) : "";
            String aiTitle = ep.get("aiTitle") != null ? ep.get("aiTitle").toString().toLowerCase(Locale.ROOT) : "";
            String aiDesc = ep.get("aiDescription") != null ? ep.get("aiDescription").toString().toLowerCase(Locale.ROOT) : "";
            String label = ep.get("label") != null ? ep.get("label").toString().toLowerCase(Locale.ROOT) : "";

            int score = 0;
            List<String> reasons = new ArrayList<>();

            // 1. Direct whole-query match
            if (!aiTitle.isBlank() && aiTitle.contains(query)) {
                score += 50;
                reasons.add("Прямое совпадение с бизнес-названием сценария");
            } else if (!aiDesc.isBlank() && aiDesc.contains(query)) {
                score += 35;
                reasons.add("Совпадение в описании бизнес-процесса");
            } else if (path.contains(query) || label.contains(query)) {
                score += 30;
                reasons.add("Совпадение в пути эндпоинта");
            }

            // 2. Word-by-word matching & Semantic Synonyms
            for (String word : words) {
                if (word.length() < 2) continue;

                if (!aiTitle.isBlank() && aiTitle.contains(word)) {
                    score += 25;
                    reasons.add("Ключевое слово '" + word + "' в названии сценария");
                }
                if (!aiDesc.isBlank() && aiDesc.contains(word)) {
                    score += 15;
                    reasons.add("Ключевое слово '" + word + "' в описании процесса");
                }
                if (path.contains(word) || label.contains(word)) {
                    score += 20;
                    reasons.add("Слово '" + word + "' в URL пути");
                }
                if (ownerKey.contains(word)) {
                    score += 15;
                    reasons.add("Слово '" + word + "' в имени контроллера");
                }

                // Check semantic dictionary
                Set<String> englishSynonyms = SYNONYMS.get(word);
                if (englishSynonyms == null) {
                    // Try prefix match (stemming)
                    for (var entry : SYNONYMS.entrySet()) {
                        if (word.startsWith(entry.getKey()) || entry.getKey().startsWith(word)) {
                            englishSynonyms = entry.getValue();
                            break;
                        }
                    }
                }

                if (englishSynonyms != null) {
                    for (String syn : englishSynonyms) {
                        if (path.contains(syn)) {
                            score += 30;
                            reasons.add("Смысловое соответствие '" + word + "' ➔ '" + syn + "' в пути");
                        }
                        if (ownerKey.contains(syn)) {
                            score += 20;
                            reasons.add("Смысловое соответствие '" + word + "' ➔ '" + syn + "' в контроллере");
                        }
                        if (syn.equalsIgnoreCase(method)) {
                            score += 25;
                            reasons.add("Действие '" + word + "' соответствует HTTP " + method);
                        }
                    }
                }
            }

            if (score > 0) {
                int finalScore = Math.min(100, score);
                String reason = reasons.isEmpty() ? "Семантическое совпадение" : reasons.getFirst();

                var item = new LinkedHashMap<String, Object>();
                item.put("endpointId", id);
                item.put("score", finalScore);
                item.put("reason", reason);
                item.put("allReasons", reasons);
                item.put("endpoint", ep);
                matched.add(item);
            }
        }

        matched.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return matched;
    }
}
