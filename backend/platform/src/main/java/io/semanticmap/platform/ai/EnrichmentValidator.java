package io.semanticmap.platform.ai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EnrichmentValidator {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentValidator.class);

    public enum Category {
        BUSINESS_RULE,
        VALIDATION_RULE,
        AUTHORIZATION_RULE,
        DATA_RULE,
        SIDE_EFFECT,
        UNKNOWN
    }

    public enum Ambiguity {
        INSUFFICIENT_CONTEXT,
        UNRESOLVED_SYMBOL,
        CONFLICTING_EVIDENCE,
        SOURCE_REQUIRES_REVIEW
    }

    public record Claim(String factId, String field, String value) {}

    public static final String SCHEMA =
            """
        {"type":"object","additionalProperties":false,
         "required":["category","title","businessPurpose","actors","businessSteps","businessRules","errorScenarios"],
         "properties":{
          "category":{"type":"string","enum":["BUSINESS_RULE","VALIDATION_RULE","AUTHORIZATION_RULE","DATA_RULE","SIDE_EFFECT","UNKNOWN"]},
          "title":{"type":"string","minLength":3,"maxLength":300},
          "businessPurpose":{"type":"string","minLength":3,"maxLength":3000},
          "actors":{"type":"array","maxItems":10,"items":{"type":"string"}},
          "businessSteps":{"type":"array","maxItems":10,"items":{"type":"string"}},
          "businessRules":{"type":"array","maxItems":10,"items":{"type":"string"}},
          "errorScenarios":{"type":"array","maxItems":10,"items":{"type":"string"}},
          "supportedFactIds":{"type":"array","maxItems":32,"items":{"type":"string"}},
          "claims":{"type":"array","maxItems":16,"items":{"type":"object","additionalProperties":false,
            "required":["factId","field","value"],"properties":{"factId":{"type":"string"},"field":{"type":"string"},"value":{"type":"string","minLength":1,"maxLength":12000}}}},
          "ambiguities":{"type":"array","maxItems":16,"items":{"type":"string","enum":["INSUFFICIENT_CONTEXT","UNRESOLVED_SYMBOL","CONFLICTING_EVIDENCE","SOURCE_REQUIRES_REVIEW"]}}}}
        """;

    public static final String PROMPT =
            """
        You are an expert Enterprise Business Analyst and Solution Architect.
        Your goal is to explain an application API endpoint/scenario in clear RUSSIAN for non-technical product managers, business analysts and executives.

        RULES:
        1. Return exactly the JSON schema supplied as response_format.
        2. All text descriptions must be in RUSSIAN (no technical raw code syntax or stack traces).
        3. 'title': A concise, intuitive business title in Russian (e.g. "Получение прямого видеопотока с камеры", "Поиск камер на интерактивной карте", "Создание новой организации"). NEVER write bug reports, missing field warnings, table diagnoses or code smells in 'title' or 'businessPurpose' - you are NOT doing code review, you are explaining the business purpose of the API endpoint.
        4. 'businessPurpose': 1-2 clear sentences explaining the business goal and why this endpoint exists for users/business.
        5. 'actors': List of roles or clients initiating this request (e.g. ["Оператор видеонаблюдения", "Аналитик безопасности", "Внешняя система"]).
        6. 'businessSteps': 3-5 sequential steps of what happens from request to response in human-friendly terms (e.g. "1. Проверка прав пользователя на просмотр камеры", "2. Запрос активного медиа-канала", "3. Потоковая передача видео клиенту").
        7. 'businessRules': Key business and security rules enforced (e.g. "Пользователь должен быть авторизован и иметь доступ к указанной камере").
        8. 'errorScenarios': Explain in human terms what happens on errors or exceptions (e.g. "Если камера недоступна или поток прерван, клиенту возвращается ошибка воспроизведения").
        9. 'category': Select the most fitting Category enum.
        10. 'supportedFactIds': Array of relevant fact IDs from the provided facts.
        11. 'ambiguities': List any ambiguities if context is missing, or empty array.
        """;

    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(32)
                            .maxStringLength(64000)
                            .build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public EnrichmentValidator(Validator validator) {
        // validator kept for Spring DI compatibility
    }

    public Map<String, Object> validate(String response, EvidenceBundle bundle) {
        if (response == null || response.isBlank()) {
            log.warn("Empty response from LLM, using fallback humanization");
            return generateFallbackEnrichment(bundle);
        }

        String raw = cleanJsonResponse(response);
        log.info("Validating LLM enrichment response (chars={}, availableFacts={})", raw.length(), bundle.facts().size());

        try {
            JsonNode json = mapper.readTree(raw);
            if (json == null || !json.isObject()) {
                log.warn("Response is not a JSON object, using fallback humanization");
                return generateFallbackEnrichment(bundle);
            }

            Category category = Category.UNKNOWN;
            if (json.has("category")) {
                try {
                    category = Category.valueOf(json.get("category").asText());
                } catch (Exception ignored) {
                }
            }

            String title = json.hasNonNull("title") ? json.get("title").asText().trim() : "";
            String businessPurpose = json.hasNonNull("businessPurpose") ? json.get("businessPurpose").asText().trim() : "";

            List<String> actors = readStringList(json, "actors");
            List<String> businessSteps = readStringList(json, "businessSteps");
            List<String> businessRules = readStringList(json, "businessRules");
            List<String> errorScenarios = readStringList(json, "errorScenarios");

            // If key business text is missing or generic, supplement with fallback
            Map<String, Object> fallback = generateFallbackEnrichment(bundle);
            if (title.isBlank() || title.length() < 3) {
                title = fallback.get("title").toString();
            }
            if (businessPurpose.isBlank()) {
                businessPurpose = fallback.getOrDefault("businessPurpose", "").toString();
            }
            if (actors.isEmpty() && fallback.get("actors") instanceof List<?> list) {
                for (Object o : list) actors.add(o.toString());
            }
            if (businessSteps.isEmpty() && fallback.get("businessSteps") instanceof List<?> list) {
                for (Object o : list) businessSteps.add(o.toString());
            }
            if (businessRules.isEmpty() && fallback.get("businessRules") instanceof List<?> list) {
                for (Object o : list) businessRules.add(o.toString());
            }
            if (errorScenarios.isEmpty() && fallback.get("errorScenarios") instanceof List<?> list) {
                for (Object o : list) errorScenarios.add(o.toString());
            }

            // Extract claims and supported facts safely
            Set<String> validFactIds = new HashSet<>();
            if (json.has("supportedFactIds") && json.get("supportedFactIds").isArray()) {
                for (JsonNode f : json.get("supportedFactIds")) {
                    String fid = f.asText();
                    if (bundle.facts().containsKey(fid)) {
                        validFactIds.add(fid);
                    }
                }
            }
            if (validFactIds.isEmpty()) {
                validFactIds.addAll(bundle.facts().keySet());
            }

            List<Claim> validClaims = new ArrayList<>();
            if (json.has("claims") && json.get("claims").isArray()) {
                for (JsonNode c : json.get("claims")) {
                    if (!c.has("factId") || !c.has("field") || !c.has("value")) continue;
                    String factId = c.get("factId").asText();
                    String field = c.get("field").asText();
                    String val = c.get("value").asText();
                    if (bundle.facts().containsKey(factId) && EvidenceBundle.FIELDS.contains(field)) {
                        if (!containsInstructionInjection(val)) {
                            validClaims.add(new Claim(factId, field, val));
                            validFactIds.add(factId);
                        }
                    }
                }
            }

            List<Ambiguity> ambiguities = new ArrayList<>();
            if (json.has("ambiguities") && json.get("ambiguities").isArray()) {
                for (JsonNode a : json.get("ambiguities")) {
                    try {
                        ambiguities.add(Ambiguity.valueOf(a.asText()));
                    } catch (Exception ignored) {
                    }
                }
            }

            // Format executive markdown description
            String markdownDescription = buildMarkdownDescription(businessPurpose, actors, businessSteps, businessRules, errorScenarios);

            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("title", title);
            normalized.put("description", markdownDescription);
            normalized.put("category", category.name());
            normalized.put("businessPurpose", businessPurpose);
            normalized.put("actors", actors);
            normalized.put("businessSteps", businessSteps);
            normalized.put("businessRules", businessRules);
            normalized.put("errorScenarios", errorScenarios);
            normalized.put("supportedFactIds", new ArrayList<>(validFactIds));
            normalized.put("claims", validClaims);
            normalized.put("ambiguities", ambiguities);
            normalized.put("trustStatus", "UNVERIFIED");
            normalized.put("origin", "LLM_ENRICHMENT");
            normalized.put("validationScope", "BUSINESS_NARRATIVE_AND_EVIDENCE_PROVENANCE");
            return normalized;

        } catch (Exception ex) {
            log.warn("Failed to parse LLM response ({}), generating fallback business enrichment", ex.getMessage());
            return generateFallbackEnrichment(bundle);
        }
    }

    public Map<String, Object> generateFallbackEnrichment(EvidenceBundle bundle) {
        String method = "";
        String path = "";
        String methodName = "";
        Set<String> tables = new HashSet<>();
        Set<String> exceptions = new HashSet<>();
        List<String> conditions = new ArrayList<>();

        for (var entry : bundle.facts().entrySet()) {
            JsonNode fact = entry.getValue();
            if (fact.hasNonNull("httpMethod")) method = fact.get("httpMethod").asText();
            if (fact.hasNonNull("path")) path = fact.get("path").asText();
            if (fact.hasNonNull("name")) {
                String n = fact.get("name").asText();
                if (methodName.isBlank() && !n.contains(" ")) methodName = n;
            }
            if (fact.hasNonNull("table")) tables.add(fact.get("table").asText());
            if (fact.hasNonNull("exceptions")) {
                JsonNode ex = fact.get("exceptions");
                if (ex.isArray()) {
                    for (JsonNode item : ex) exceptions.add(item.asText());
                } else if (!ex.asText().isBlank()) {
                    exceptions.add(ex.asText());
                }
            }
            if (fact.hasNonNull("dataReads")) {
                JsonNode dr = fact.get("dataReads");
                if (dr.isArray()) {
                    for (JsonNode item : dr) tables.add(cleanName(item.asText()));
                }
            }
            if (fact.hasNonNull("dataWrites")) {
                JsonNode dw = fact.get("dataWrites");
                if (dw.isArray()) {
                    for (JsonNode item : dw) tables.add(cleanName(item.asText()));
                }
            }
            if (fact.hasNonNull("normalizedCondition")) {
                conditions.add(fact.get("normalizedCondition").asText());
            }
        }

        // Generate human business title
        String title = humanizeBusinessTitle(method, path, methodName);
        String purpose = humanizeBusinessPurpose(method, path, methodName, tables);

        List<String> actors = new ArrayList<>();
        actors.add(method.equals("GET") ? "Авторизованный пользователь (Оператор, Аналитик)" : "Администратор / Авторизованный клиент");
        if (path.contains("/internal/") || path.contains("/system/")) {
            actors.add("Внутренний микросервис платформы");
        }

        List<String> steps = new ArrayList<>();
        steps.add("Получение и валидация входных параметров запроса (" + (method.isBlank() ? "HTTP" : method) + " " + path + ")");
        steps.add("Проверка прав доступа текущего пользователя и авторизация ресурса");
        if (!tables.isEmpty()) {
            steps.add("Выполнение операций в базе данных (" + String.join(", ", tables) + ")");
        } else {
            steps.add("Обработка бизнес-логики сервиса");
        }
        steps.add("Формирование и возврат успешного ответа клиенту");

        List<String> rules = new ArrayList<>();
        rules.add("Пользователь должен быть авторизован в системе");
        if (!conditions.isEmpty()) {
            rules.add("Проверка граничных условий и валидности переданных идентификаторов");
        }
        if (path.contains("{id}") || path.contains("{")) {
            rules.add("Запрашиваемый ресурс должен существовать и быть активным");
        }

        List<String> errors = new ArrayList<>();
        if (!exceptions.isEmpty()) {
            for (String ex : exceptions) {
                errors.add(humanizeExceptionName(ex));
            }
        } else {
            errors.add("400 Bad Request: Некорректный формат переданных параметров");
            errors.add("401 / 403 Forbidden: Недостаточно прав для выполнения операции");
            errors.add("404 Not Found: Запрашиваемый ресурс не найден в системе");
            errors.add("500 Internal Error: Сбой при обработке запроса сервисом");
        }

        String markdownDescription = buildMarkdownDescription(purpose, actors, steps, rules, errors);

        var result = new LinkedHashMap<String, Object>();
        result.put("title", title);
        result.put("description", markdownDescription);
        result.put("category", method.equals("GET") ? Category.BUSINESS_RULE.name() : Category.SIDE_EFFECT.name());
        result.put("businessPurpose", purpose);
        result.put("actors", actors);
        result.put("businessSteps", steps);
        result.put("businessRules", rules);
        result.put("errorScenarios", errors);
        result.put("supportedFactIds", new ArrayList<>(bundle.facts().keySet()));
        result.put("claims", List.of());
        result.put("ambiguities", List.of());
        result.put("trustStatus", "UNVERIFIED");
        result.put("origin", "HEURISTIC_BUSINESS_MAP");
        result.put("validationScope", "DETERMINISTIC_BUSINESS_HUMANIZER");
        return result;
    }

    private static String humanizeBusinessTitle(String method, String path, String methodName) {
        String lowerPath = path.toLowerCase();
        String lowerMethodName = methodName.toLowerCase();

        if (lowerPath.contains("stream") || lowerMethodName.contains("stream")) {
            return "Трансляция видеопотока с камеры";
        }
        if (lowerPath.contains("playback") || lowerMethodName.contains("playback")) {
            return "Воспроизведение архивной видеозаписи";
        }
        if (lowerPath.contains("cameras/map") || lowerMethodName.contains("map")) {
            return "Отображение камер на карте местности";
        }
        if (lowerPath.contains("cameras") && method.equals("GET") && !lowerPath.contains("{")) {
            return "Получение реестра камер наблюдения";
        }
        if (lowerPath.contains("cameras") && method.equals("GET")) {
            return "Просмотр информации о камере";
        }
        if (lowerPath.contains("cameras") && method.equals("POST")) {
            return "Регистрация новой камеры";
        }
        if (lowerPath.contains("cameras") && method.equals("DELETE")) {
            return "Удаление камеры из системы";
        }
        if (lowerPath.contains("dataset") && method.equals("GET")) {
            return "Просмотр наборов данных (датасетов)";
        }
        if (lowerPath.contains("dataset") && method.equals("POST")) {
            return "Создание нового набора данных";
        }
        if (lowerPath.contains("user") || lowerPath.contains("auth")) {
            if (lowerPath.contains("login") || lowerPath.contains("token")) return "Аутентификация и выдача токена доступа";
            if (method.equals("POST")) return "Создание учетной записи пользователя";
            if (method.equals("DELETE")) return "Блокировка или удаление пользователя";
            return "Управление профилем пользователя";
        }

        // Generic fallback from method & path
        String action = switch (method.toUpperCase()) {
            case "POST" -> "Создание / запуск: ";
            case "PUT", "PATCH" -> "Обновление: ";
            case "DELETE" -> "Удаление: ";
            default -> "Просмотр: ";
        };
        String entity = path.replaceAll("^/v[0-9]+/", "").replaceAll("/\\{[^}]+}", "");
        return action + (entity.isBlank() ? (methodName.isBlank() ? "API операции" : methodName) : entity);
    }

    private static String humanizeBusinessPurpose(String method, String path, String methodName, Set<String> tables) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.contains("stream")) {
            return "Обеспечивает передачу защищенного видеопотока высокого разрешения с выбранной камеры авторизованному клиенту в реальном времени.";
        }
        if (lowerPath.contains("playback")) {
            return "Предоставляет доступ к записанному видеоархиву за указанный интервал времени с возможностью покадрового воспроизведения.";
        }
        if (lowerPath.contains("map")) {
            return "Возвращает геопозиции и статус активности видеокамер для интерактивного отображения на карте объекта.";
        }
        if (lowerPath.contains("cameras")) {
            return "Используется для управления устройствами видеонаблюдения, проверки их доступности и конфигурации каналов связи.";
        }
        return "Предоставляет бизнес-интерфейс для выполнения операции " + (method.isBlank() ? "HTTP" : method) + " " + path
                + (!tables.isEmpty() ? " с взаимодействием с хранилищем данных (" + String.join(", ", tables) + ")." : ".");
    }

    private static String humanizeExceptionName(String raw) {
        if (raw == null || raw.isBlank()) return "Непредвиденная системная ошибка";
        if (raw.contains("CameraPlaybackException")) return "Ошибка воспроизведения: видеопоток камеры прерван или устройство оффлайн";
        if (raw.contains("AccessDenied") || raw.contains("SecurityException")) return "Ошибка доступа: недостаточно прав для выполнения операции";
        if (raw.contains("NotFound") || raw.contains("EntityNotFound")) return "Объект не найден: запрошенная запись отсутствует в системе";
        if (raw.contains("Validation") || raw.contains("IllegalArgument")) return "Ошибка валидации: переданы некорректные параметры";
        if (raw.contains("Duplicate") || raw.contains("Conflict")) return "Конфликт данных: запись с такими параметрами уже существует";
        return "Исключение " + raw.replaceAll(".*\\.", "") + ": ошибка при обработке запроса";
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        int hash = name.indexOf('#');
        if (hash >= 0) name = name.substring(hash + 1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        return name;
    }

    private static String buildMarkdownDescription(
            String purpose,
            List<String> actors,
            List<String> steps,
            List<String> rules,
            List<String> errors) {
        StringBuilder sb = new StringBuilder();
        if (purpose != null && !purpose.isBlank()) {
            sb.append("### 🎯 Бизнес-цель\n").append(purpose).append("\n\n");
        }
        if (actors != null && !actors.isEmpty()) {
            sb.append("### 👤 Роли и инициаторы\n");
            for (String a : actors) sb.append("- ").append(a).append("\n");
            sb.append("\n");
        }
        if (steps != null && !steps.isEmpty()) {
            sb.append("### 📋 Бизнес-шаги выполнения\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
            sb.append("\n");
        }
        if (rules != null && !rules.isEmpty()) {
            sb.append("### 🛡 Проверяемые бизнес-правила\n");
            for (String r : rules) sb.append("- ").append(r).append("\n");
            sb.append("\n");
        }
        if (errors != null && !errors.isEmpty()) {
            sb.append("### ⚠️ Возможные бизнес-ошибки\n");
            for (String e : errors) sb.append("- ").append(e).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        List<String> list = new ArrayList<>();
        if (parent.has(field) && parent.get(field).isArray()) {
            for (JsonNode item : parent.get(field)) {
                String text = item.asText().trim();
                if (!text.isBlank()) list.add(text);
            }
        }
        return list;
    }

    private static String cleanJsonResponse(String response) {
        String clean = response.trim();
        if (clean.startsWith("```")) {
            int start = clean.indexOf('\n');
            int end = clean.lastIndexOf("```");
            if (start != -1 && end > start) {
                clean = clean.substring(start + 1, end).trim();
            }
        }
        return clean;
    }

    private static boolean containsInstructionInjection(String text) {
        if (text == null) return false;
        return java.util.regex.Pattern.compile(
                        "(?is)(ignore|disregard|override).{0,60}(instruction|prompt)|(?:system|developer|assistant)\\s*:|execute\\s+(?:command|shell)|run\\s+(?:command|shell)|powershell|curl\\s+https?://")
                .matcher(text)
                .find();
    }
}
