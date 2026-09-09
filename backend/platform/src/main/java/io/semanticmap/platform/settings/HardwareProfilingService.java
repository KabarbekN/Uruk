package io.semanticmap.platform.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HardwareProfilingService {
    private final ObjectMapper mapper;
    private final String ollamaBaseUrl;
    private final HttpClient httpClient;
    private final AiConfiguration aiConfiguration;

    public HardwareProfilingService(
            ObjectMapper mapper,
            @Value("${semantic.ai.ollama-url:http://localhost:11434}") String ollamaBaseUrl,
            AiConfiguration aiConfiguration) {
        this.mapper = mapper;
        this.ollamaBaseUrl = ollamaBaseUrl.strip().replaceAll("/+$", "");
        this.aiConfiguration = aiConfiguration;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public record HardwareProfile(
            String osName,
            String osArch,
            int cpuCores,
            long totalRamBytes,
            long freeRamBytes,
            String gpuName,
            long gpuVramBytes,
            boolean ollamaAvailable,
            String ollamaUrl,
            String activeModel,
            boolean isLocalActive,
            List<InstalledModel> installedModels,
            List<CatalogModel> catalog,
            String recommendedModelId,
            String recommendationReason) {}

    public record InstalledModel(String name, long sizeBytes, String modifiedAt) {}

    public record CatalogModel(
            String id,
            String name,
            String providerType, // "OLLAMA" or "REMOTE"
            String parameterSize,
            long requiredRamBytes,
            String description,
            String category, // "lightweight", "optimal", "power", "enterprise", "reasoning", "cloud"
            String bestFor,
            String hardwareReqs,
            boolean isRecommended,
            boolean isInstalled) {}

    public HardwareProfile getProfile() {
        String osName = System.getProperty("os.name", "Unknown");
        String osArch = System.getProperty("os.arch", "Unknown");
        int cpuCores = Runtime.getRuntime().availableProcessors();

        long totalRam = 0;
        long freeRam = 0;
        try {
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                totalRam = sunBean.getTotalMemorySize();
                freeRam = sunBean.getFreeMemorySize();
            }
        } catch (Throwable ignored) {
        }

        GpuInfo gpu = probeGpu();
        OllamaInfo ollama = probeOllama();

        List<CatalogModel> catalog = buildCatalog(totalRam, gpu.vramBytes(), ollama.installed());
        String recommendedId = determineRecommended(totalRam, gpu.vramBytes());
        String reason = buildReason(totalRam, gpu.vramBytes(), recommendedId);

        String activeModel = aiConfiguration != null ? aiConfiguration.model() : "";
        boolean isLocal = aiConfiguration != null && aiConfiguration.isLocal();

        return new HardwareProfile(
                osName,
                osArch,
                cpuCores,
                totalRam,
                freeRam,
                gpu.name(),
                gpu.vramBytes(),
                ollama.available(),
                ollamaBaseUrl,
                activeModel,
                isLocal,
                ollama.installed(),
                catalog,
                recommendedId,
                reason);
    }

    private record GpuInfo(String name, long vramBytes) {}

    private GpuInfo probeGpu() {
        try {
            Process process = new ProcessBuilder(
                            "nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (finished && process.exitValue() == 0) {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        String[] parts = line.split(",");
                        String name = parts[0].trim();
                        long mb = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0;
                        return new GpuInfo(name, mb * 1024 * 1024);
                    }
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {
        }
        return new GpuInfo(null, 0);
    }

    private record OllamaInfo(boolean available, List<InstalledModel> installed) {}

    private OllamaInfo probeOllama() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode modelsNode = root.get("models");
                var models = new ArrayList<InstalledModel>();
                if (modelsNode != null && modelsNode.isArray()) {
                    for (JsonNode m : modelsNode) {
                        String name = m.path("name").asText();
                        long size = m.path("size").asLong(0);
                        String modified = m.path("modified_at").asText();
                        models.add(new InstalledModel(name, size, modified));
                    }
                }
                return new OllamaInfo(true, models);
            }
        } catch (Throwable ignored) {
        }
        return new OllamaInfo(false, List.of());
    }

    private boolean isInstalled(List<InstalledModel> installed, String modelId) {
        String baseId = modelId.contains(":") ? modelId.substring(0, modelId.indexOf(":")) : modelId;
        return installed.stream()
                .anyMatch(m -> m.name().startsWith(modelId) || m.name().startsWith(baseId));
    }

    private List<CatalogModel> buildCatalog(long totalRam, long vram, List<InstalledModel> installed) {
        var list = new ArrayList<CatalogModel>();
        String rec = determineRecommended(totalRam, vram);

        // === 1. Ультралегкие модели (до 8 GB RAM) ===
        list.add(new CatalogModel(
                "qwen2.5-coder:1.5b",
                "Qwen 2.5 Coder 1.5B",
                "OLLAMA",
                "1.5B (Q4_K_M)",
                2L * 1024 * 1024 * 1024,
                "Ультралегкая модель, минимальные требования к памяти (быстрый старт и низкая задержка).",
                "lightweight",
                "Мгновенный базовый анализ эндпоинтов, разбор контроллеров и условий без нагрузки на CPU",
                "RAM: от 3 GB | CPU: 2+ ядра | GPU: не требуется | Скорость: ~25-40 ток/сек",
                "qwen2.5-coder:1.5b".equals(rec),
                isInstalled(installed, "qwen2.5-coder:1.5b")));

        list.add(new CatalogModel(
                "deepseek-r1:1.5b",
                "DeepSeek R1 1.5B (Reasoning)",
                "OLLAMA",
                "1.5B (Q4_K_M)",
                2L * 1024 * 1024 * 1024,
                "Компактная модель логических рассуждений (Reasoning) с пошаговым выводом.",
                "reasoning",
                "Быстрые логические рассуждения при ультранизких требованиях к памяти",
                "RAM: от 3 GB | CPU: 2+ ядра | GPU: не требуется | Скорость: ~25 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:1.5b")));

        list.add(new CatalogModel(
                "llama3.2:1b",
                "Meta Llama 3.2 1B",
                "OLLAMA",
                "1.2B (Q4_K_M)",
                2L * 1024 * 1024 * 1024,
                "Сверхбыстрая компактная модель от Meta для мгновенного отклика.",
                "lightweight",
                "Сверхбыстрая фильтрация, суммаризация и базовая категоризация",
                "RAM: от 2 GB | CPU: 2 ядра | GPU: не требуется | Скорость: ~35-50 ток/сек",
                false,
                isInstalled(installed, "llama3.2:1b")));

        list.add(new CatalogModel(
                "llama3.2:3b",
                "Meta Llama 3.2 3B",
                "OLLAMA",
                "3.2B (Q4_K_M)",
                3L * 1024 * 1024 * 1024,
                "Идеальный баланс размера и качества для легких серверов и ноутбуков.",
                "lightweight",
                "Генерация естественных текстов и понятных описаний API",
                "RAM: от 4 GB | CPU: 2-4 ядра | GPU: не требуется | Скорость: ~20-30 ток/сек",
                false,
                isInstalled(installed, "llama3.2:3b")));

        list.add(new CatalogModel(
                "qwen2.5-coder:3b",
                "Qwen 2.5 Coder 3B",
                "OLLAMA",
                "3B (Q4_K_M)",
                3L * 1024 * 1024 * 1024,
                "Программирование и точная генерация структурированного кода при скромных ресурсах.",
                "lightweight",
                "Генерация синтаксических деревьев и правил для слабых VPS без GPU",
                "RAM: от 4 GB | CPU: 4 ядра | GPU: не требуется | Скорость: ~20 ток/сек",
                false,
                isInstalled(installed, "qwen2.5-coder:3b")));

        list.add(new CatalogModel(
                "phi3:mini",
                "Microsoft Phi-3 Mini 3.8B",
                "OLLAMA",
                "3.8B (Q4_K_M)",
                4L * 1024 * 1024 * 1024,
                "Компактная и быстрая модель от Microsoft с великолепным логическим пониманием.",
                "lightweight",
                "Точный синтаксический анализ и понимание архитектурных паттернов",
                "RAM: от 4 GB | CPU: 4 ядра | GPU: не требуется | Скорость: ~18 ток/сек",
                false,
                isInstalled(installed, "phi3:mini")));

        list.add(new CatalogModel(
                "gemma2:2b",
                "Google Gemma 2 2B",
                "OLLAMA",
                "2.6B (Q4_K_M)",
                3L * 1024 * 1024 * 1024,
                "Энергоэффективная модель от Google для быстрого семантического распознавания.",
                "lightweight",
                "Быстрая семантическая классификация и краткие аннотации",
                "RAM: от 3 GB | CPU: 2-4 ядра | GPU: не требуется | Скорость: ~25 ток/сек",
                false,
                isInstalled(installed, "gemma2:2b")));

        // === 2. Оптимальные рабочие лошадки (8–16 GB RAM / GPU 6–8 GB) ===
        list.add(new CatalogModel(
                "qwen2.5-coder:7b",
                "Qwen 2.5 Coder 7B",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Рекомендуемый баланс качества и скорости для большинства серверов (8-16 GB RAM).",
                "optimal",
                "Золотой стандарт реверс-инжиниринга Spring/Node.js, точный разбор методов и SQL",
                "RAM: от 8 GB | GPU: рекомендуется 6-8 GB VRAM (RTX 3060/4060) | Скорость: ~15-35 ток/сек",
                "qwen2.5-coder:7b".equals(rec),
                isInstalled(installed, "qwen2.5-coder:7b")));

        list.add(new CatalogModel(
                "deepseek-r1:7b",
                "DeepSeek R1 7B (Reasoning)",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Модель глубоких логических рассуждений для сложного анализа архитектуры и неявных зависимостей.",
                "reasoning",
                "Глубокие логические рассуждения, выявление неявных зависимостей сервисов и транзакций",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~12-25 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:7b")));

        list.add(new CatalogModel(
                "deepseek-r1:8b",
                "DeepSeek R1 8B (Llama)",
                "OLLAMA",
                "8B (Q4_K_M)",
                9L * 1024 * 1024 * 1024,
                "Продвинутые рассуждения от DeepSeek на базе проверенной архитектуры Llama 3.1.",
                "reasoning",
                "Продвинутый логический анализ бизнес-логики с развернутыми цепочками рассуждений",
                "RAM: от 10 GB | GPU: 8 GB VRAM | Скорость: ~12-25 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:8b")));

        list.add(new CatalogModel(
                "llama3.1:8b",
                "Meta Llama 3.1 8B",
                "OLLAMA",
                "8B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Популярная модель от Meta с отличным пониманием инструкций и богатым русским языком.",
                "optimal",
                "Общее описание бизнес-процессов, документация API на понятном русском языке",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~15-30 ток/сек",
                "llama3.1:8b".equals(rec),
                isInstalled(installed, "llama3.1:8b")));

        list.add(new CatalogModel(
                "qwen2.5:7b",
                "Qwen 2.5 7B (General)",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Универсальная модель Qwen с выдающимся качеством связного текста и мультиязычностью.",
                "optimal",
                "Универсальный анализ предметных областей, отличный русский язык и форматирование",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~15-30 ток/сек",
                false,
                isInstalled(installed, "qwen2.5:7b")));

        list.add(new CatalogModel(
                "codellama:7b",
                "Meta CodeLlama 7B",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Специализированная кодовая модель от Meta для анализа функций и API.",
                "optimal",
                "Классический разбор сигнатур функций, наследования и AST Java/Python от Meta",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~15-28 ток/сек",
                false,
                isInstalled(installed, "codellama:7b")));

        list.add(new CatalogModel(
                "gemma2:9b",
                "Google Gemma 2 9B",
                "OLLAMA",
                "9B (Q4_K_M)",
                10L * 1024 * 1024 * 1024,
                "Современная модель от Google с глубоким контекстом и качественным русским языком.",
                "optimal",
                "Высокоточный анализ структуры проектов от Google DeepMind",
                "RAM: от 10 GB | GPU: 8-10 GB VRAM | Скорость: ~12-24 ток/сек",
                false,
                isInstalled(installed, "gemma2:9b")));

        list.add(new CatalogModel(
                "starcoder2:7b",
                "StarCoder2 7B (BigCode)",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Модель проекта BigCode, обученная на 80+ языках программирования и открытых репозиториях.",
                "optimal",
                "Специализированный анализ кода полиглот-репозиториев (Java, Go, Python, TS, SQL)",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~15-28 ток/сек",
                false,
                isInstalled(installed, "starcoder2:7b")));

        list.add(new CatalogModel(
                "mistral:7b",
                "Mistral 7B Instruct",
                "OLLAMA",
                "7B (Q4_K_M)",
                8L * 1024 * 1024 * 1024,
                "Классическая быстрая модель Mistral AI со строгим следованием форматам вывода.",
                "optimal",
                "Строгое следование системным промптам и высокая плотность ответов",
                "RAM: от 8 GB | GPU: 6-8 GB VRAM | Скорость: ~15-30 ток/сек",
                false,
                isInstalled(installed, "mistral:7b")));

        // === 3. Мощные модели для углубленного анализа (16–32 GB RAM / GPU 12–16 GB) ===
        list.add(new CatalogModel(
                "mistral-nemo:12b",
                "Mistral NeMo 12B (128k context)",
                "OLLAMA",
                "12B (Q4_K_M)",
                12L * 1024 * 1024 * 1024,
                "Разработана Mistral совместно с NVIDIA: контекстное окно 128k токенов для крупных файлов.",
                "power",
                "Огромное контекстное окно (128k токенов), чтение больших классов и файлов настроек",
                "RAM: от 14 GB | GPU: 12 GB VRAM (RTX 3060 12GB / 4070) | Скорость: ~12-20 ток/сек",
                false,
                isInstalled(installed, "mistral-nemo:12b")));

        list.add(new CatalogModel(
                "qwen2.5-coder:14b",
                "Qwen 2.5 Coder 14B",
                "OLLAMA",
                "14B (Q4_K_M)",
                16L * 1024 * 1024 * 1024,
                "Высокая точность анализа сложной бизнес-логики (требуется 16+ GB RAM или GPU).",
                "power",
                "Промышленный реверс-инжиниринг: безошибочно связывает контроллеры, сервисы и SQL-схемы",
                "RAM: от 16 GB | GPU: 12-16 GB VRAM | Скорость: ~10-18 ток/сек",
                "qwen2.5-coder:14b".equals(rec),
                isInstalled(installed, "qwen2.5-coder:14b")));

        list.add(new CatalogModel(
                "deepseek-r1:14b",
                "DeepSeek R1 14B (Reasoning)",
                "OLLAMA",
                "14B (Q4_K_M)",
                16L * 1024 * 1024 * 1024,
                "Продвинутая модель рассуждений для глубокой декомпозиции архитектурных слоев.",
                "reasoning",
                "Продвинутый поиск скрытых зависимостей и архитектурных аномалий в микросервисах",
                "RAM: от 16 GB | GPU: 12-16 GB VRAM | Скорость: ~10-18 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:14b")));

        list.add(new CatalogModel(
                "deepseek-coder-v2:16b",
                "DeepSeek Coder V2 16B",
                "OLLAMA",
                "16B MoE (Q4_K_M)",
                14L * 1024 * 1024 * 1024,
                "Одна из лучших открытых моделей для глубокого разбора кода и бизнес-правил (MoE).",
                "power",
                "Архитектура Mixture-of-Experts: выдающаяся глубина понимания Java Spring и JPA",
                "RAM: от 16 GB | GPU: 12-16 GB VRAM | Скорость: ~12-20 ток/сек",
                false,
                isInstalled(installed, "deepseek-coder-v2:16b")));

        list.add(new CatalogModel(
                "codestral:22b",
                "Mistral Codestral 22B",
                "OLLAMA",
                "22B (Q4_K_M)",
                20L * 1024 * 1024 * 1024,
                "Флагманский специализированный кодовый генератор от Mistral AI с поддержкой FIM.",
                "power",
                "Флагманский кодовый анализ Mistral AI для проектов со сложными ветвлениями",
                "RAM: от 24 GB | GPU: 16-24 GB VRAM (RTX 4090 / A5000) | Скорость: ~8-15 ток/сек",
                false,
                isInstalled(installed, "codestral:22b")));

        list.add(new CatalogModel(
                "phi4:14b",
                "Microsoft Phi-4 14B",
                "OLLAMA",
                "14B (Q4_K_M)",
                16L * 1024 * 1024 * 1024,
                "Новейшая модель Microsoft с акцентом на математическую логику и строгость рассуждений.",
                "power",
                "Новейшая логическая модель Microsoft для синтеза комплексных архитектурных отчетов",
                "RAM: от 16 GB | GPU: 12-16 GB VRAM | Скорость: ~10-18 ток/сек",
                false,
                isInstalled(installed, "phi4:14b")));

        list.add(new CatalogModel(
                "qwen2.5:14b",
                "Qwen 2.5 14B (General)",
                "OLLAMA",
                "14B (Q4_K_M)",
                16L * 1024 * 1024 * 1024,
                "Мощная универсальная модель для генерации исчерпывающей бизнес-документации.",
                "power",
                "Подробные бизнес-описания для продуктовых аналитиков и архитекторов",
                "RAM: от 16 GB | GPU: 12-16 GB VRAM | Скорость: ~10-18 ток/сек",
                false,
                isInstalled(installed, "qwen2.5:14b")));

        // === 4. Тяжелые и Enterprise модели (32–64+ GB RAM / GPU 24–48+ GB) ===
        list.add(new CatalogModel(
                "qwen2.5-coder:32b",
                "Qwen 2.5 Coder 32B",
                "OLLAMA",
                "32B (Q4_K_M)",
                28L * 1024 * 1024 * 1024,
                "Максимальное качество рассуждений, корпоративный уровень (требуется 32+ GB RAM).",
                "enterprise",
                "Топовое качество анализа открытого ПО: сопоставимо с закрытыми флагманами GPT-4o",
                "RAM: от 32 GB | GPU: от 24 GB VRAM (RTX 3090 / 4090 / A5000) | Скорость: ~6-12 ток/сек",
                "qwen2.5-coder:32b".equals(rec),
                isInstalled(installed, "qwen2.5-coder:32b")));

        list.add(new CatalogModel(
                "deepseek-r1:32b",
                "DeepSeek R1 32B (Reasoning)",
                "OLLAMA",
                "32B (Q4_K_M)",
                28L * 1024 * 1024 * 1024,
                "Флагманские рассуждения в полностью закрытом локальном контуре предприятия.",
                "reasoning",
                "Максимально мощные рассуждения в полностью закрытом On-Premise контуре без передачи вовне",
                "RAM: от 32 GB | GPU: от 24 GB VRAM | Скорость: ~6-12 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:32b")));

        list.add(new CatalogModel(
                "command-r:35b",
                "Cohere Command-R 35B",
                "OLLAMA",
                "35B (Q4_K_M)",
                32L * 1024 * 1024 * 1024,
                "Оптимизирована для RAG и связывания больших массивов структурированных данных.",
                "enterprise",
                "RAG и работа с гигантскими таблицами зависимостей и документации",
                "RAM: от 36 GB | GPU: от 24 GB VRAM | Скорость: ~5-10 ток/сек",
                false,
                isInstalled(installed, "command-r:35b")));

        list.add(new CatalogModel(
                "llama3.1:70b",
                "Meta Llama 3.1 70B",
                "OLLAMA",
                "70B (Q4_K_M)",
                55L * 1024 * 1024 * 1024,
                "Enterprise-гигант от Meta: уровень ведущих коммерческих облачных моделей.",
                "enterprise",
                "Корпоративная замена облачным ИИ для монолитов в закрытом контуре безопасности",
                "RAM: от 64 GB | GPU: 2x 24 GB (или 1x A100 80GB) | Скорость: ~4-8 ток/сек",
                false,
                isInstalled(installed, "llama3.1:70b")));

        list.add(new CatalogModel(
                "deepseek-r1:70b",
                "DeepSeek R1 70B (Reasoning)",
                "OLLAMA",
                "70B (Q4_K_M)",
                55L * 1024 * 1024 * 1024,
                "Абсолютный флагман открытых моделей рассуждений с колоссальной глубиной мысли.",
                "reasoning",
                "Ультимативный уровень рассуждений и реверс-инжиниринга для Enterprise-серверов",
                "RAM: от 64 GB | GPU: 2x 24 GB (RTX 3090/4090) или A100 | Скорость: ~3-6 ток/сек",
                false,
                isInstalled(installed, "deepseek-r1:70b")));

        // === 5. Облачные супермодели (Remote API — 0 байт RAM на сервере) ===
        list.add(new CatalogModel(
                "gpt-4o",
                "OpenAI GPT-4o",
                "REMOTE",
                "Cloud API",
                0,
                "Флагманская мультимодальная модель OpenAI: максимальная глубина анализа, не нагружает сервер.",
                "cloud",
                "Универсальный флагман OpenAI: максимальная глубина анализа, сложные алгоритмы",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ OpenAI",
                "gpt-4o".equals(rec),
                false));

        list.add(new CatalogModel(
                "gpt-4o-mini",
                "OpenAI GPT-4o Mini",
                "REMOTE",
                "Cloud API",
                0,
                "Сверхбыстрая и экономичная модель OpenAI для мгновенного анализа архитектуры.",
                "cloud",
                "Сверхбыстрая и экономичная модель для непрерывного анализа в CI/CD пайплайнах",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ OpenAI",
                false,
                false));

        list.add(new CatalogModel(
                "o3-mini",
                "OpenAI o3-mini (Reasoning)",
                "REMOTE",
                "Cloud API",
                0,
                "Новейшая модель рассуждений от OpenAI для комплексного реверс-инжиниринга проектов.",
                "cloud",
                "Аппаратные рассуждения OpenAI: распутывание циклических зависимостей и легаси",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ OpenAI",
                false,
                false));

        list.add(new CatalogModel(
                "claude-3-7-sonnet",
                "Anthropic Claude 3.7 Sonnet",
                "REMOTE",
                "Cloud API",
                0,
                "Флагманская модель с гибридным мышлением и лучшим в индустрии пониманием исходного кода.",
                "cloud",
                "Гибридное мышление, топ-1 в мировых бенчмарках по пониманию исходного кода",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ Anthropic",
                false,
                false));

        list.add(new CatalogModel(
                "claude-3-5-sonnet",
                "Anthropic Claude 3.5 Sonnet",
                "REMOTE",
                "Cloud API",
                0,
                "Золотой стандарт для реверс-инжиниринга и документации API от Anthropic.",
                "cloud",
                "Золотой стандарт архитектурного анализа и точного описания бизнес-процессов",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ Anthropic",
                false,
                false));

        list.add(new CatalogModel(
                "claude-3-5-haiku",
                "Anthropic Claude 3.5 Haiku",
                "REMOTE",
                "Cloud API",
                0,
                "Сверхбыстрый отклик при высоком качестве извлечения сущностей.",
                "cloud",
                "Мгновенные ответы и выделение сущностей при минимальной стоимости запросов",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ Anthropic",
                false,
                false));

        list.add(new CatalogModel(
                "gemini-2.5-pro",
                "Google Gemini 2.5 Pro",
                "REMOTE",
                "Cloud API",
                0,
                "Модель с миллионным контекстом от Google: видит весь репозиторий целиком.",
                "cloud",
                "Контекстное окно в 1 000 000 токенов: способен прочесть весь монолит репозитория целиком",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ Google AI Studio",
                false,
                false));

        list.add(new CatalogModel(
                "gemini-2.5-flash",
                "Google Gemini 2.5 Flash",
                "REMOTE",
                "Cloud API",
                0,
                "Быстрая и доступная облачная модель от Google для ежедневного использования.",
                "cloud",
                "Высокая скорость, превосходное владение русским языком и доступная цена",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ Google AI Studio",
                false,
                false));

        list.add(new CatalogModel(
                "deepseek-chat",
                "DeepSeek V3 / R1 (Cloud API)",
                "REMOTE",
                "Cloud API",
                0,
                "Прямой доступ к облачному API DeepSeek: непревзойденная цена и мощь.",
                "cloud",
                "Флагманский уровень качества по цене в 10-20 раз ниже американских провайдеров",
                "RAM: 0 GB (внешний сервер) | Нужен API-ключ api.deepseek.com",
                false,
                false));

        return list;
    }

    private String determineRecommended(long totalRam, long vram) {
        long effectiveMem = Math.max(totalRam, vram * 2);
        if (effectiveMem >= 30L * 1024 * 1024 * 1024) {
            return "qwen2.5-coder:14b";
        } else if (effectiveMem >= 10L * 1024 * 1024 * 1024 || vram >= 6L * 1024 * 1024 * 1024) {
            return "qwen2.5-coder:7b";
        } else if (totalRam >= 6L * 1024 * 1024 * 1024) {
            return "qwen2.5-coder:1.5b";
        } else {
            return "gpt-4o";
        }
    }

    private String buildReason(long totalRam, long vram, String rec) {
        long ramGb = totalRam / (1024 * 1024 * 1024);
        long vramGb = vram / (1024 * 1024 * 1024);
        if (rec.equals("qwen2.5-coder:14b")) {
            return "Сервер имеет достаточно ресурсов (" + ramGb + " GB RAM"
                    + (vramGb > 0 ? ", " + vramGb + " GB VRAM GPU" : "")
                    + ") для уверенного запуска Qwen 2.5 Coder 14B.";
        } else if (rec.equals("qwen2.5-coder:7b")) {
            return "Оптимальный выбор под ресурсы сервера (" + ramGb + " GB RAM"
                    + (vramGb > 0 ? ", " + vramGb + " GB VRAM GPU" : "")
                    + "): Qwen 2.5 Coder 7B обеспечит баланс скорости и глубины анализа.";
        } else if (rec.equals("qwen2.5-coder:1.5b")) {
            return "Для сервера с " + ramGb + " GB RAM рекомендуется легкая Qwen 2.5 Coder 1.5B.";
        } else {
            return "Мало свободной памяти (" + ramGb
                    + " GB RAM). Рекомендуется использовать облачную модель (OpenAI / Claude / Gemini).";
        }
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }
}
