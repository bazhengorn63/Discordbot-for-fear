import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FearDiscordBot extends ListenerAdapter {

    // --- НАСТРОЙКИ (ТЕПЕРЬ БЕРУТСЯ С СЕРВЕРА) ---
    private static final String TOKEN = System.getenv("DISCORD_TOKEN");
    private static final long LOG_CHANNEL_ID = (System.getenv("LOG_CHANNEL_ID") != null) ? Long.parseLong(System.getenv("LOG_CHANNEL_ID")) : 0L;
    private static String SITE_ACCESS_TOKEN = System.getenv("SITE_ACCESS_TOKEN");

    // Хранилище для передачи данных между командой и нажатием кнопок
    // Ключ - ID сообщения, Значение - данные запроса (теперь содержит список SteamID)
    private static final Map<String, BanRequestData> activeRequests = new ConcurrentHashMap<>();

    private static final HttpClient httpClient = HttpClient.newBuilder().build();

    public static void main(String[] args) {
        if (TOKEN == null || TOKEN.isEmpty()) {
            System.err.println("ОШИБКА: Токен бота не найден! Добавьте переменную DISCORD_TOKEN на сервере.");
            return;
        }

        JDA jda = JDABuilder.createLight(TOKEN)
                .addEventListeners(new FearDiscordBot())
                .build();

        // Регистрация слэш-команд
        jda.updateCommands().addCommands(
                Commands.slash("cookieadmin", "Обновить токен сайта (access_token)")
                        .addOption(OptionType.STRING, "token", "Новый токен", true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)),

                Commands.slash("change-ban", "Отправить запрос на изменение бана")
                        .addOption(OptionType.STRING, "steamid", "SteamID64 нарушителя (можно несколько через пробел)", true)
                        .addOption(OptionType.STRING, "time", "Новое время (60d, 12h, 30m, 0 - пермабан)", true)
                        .addOption(OptionType.STRING, "reason", "Текст запроса для старшей администрации", true)
                        .addOption(OptionType.STRING, "site_reason", "Новая причина, которая будет видна на сайте", true)
        ).queue();
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        System.out.println("Бот " + event.getJDA().getSelfUser().getName() + " запущен и готов к работе!");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "cookieadmin" -> handleCookieAdmin(event);
            case "change-ban" -> handleChangeBan(event);
        }
    }

    private void handleCookieAdmin(SlashCommandInteractionEvent event) {
        String token = event.getOption("token").getAsString().trim();
        SITE_ACCESS_TOKEN = token;
        event.reply("✅ Токен успешно загружен в память бота! (Длина: " + token.length() + ")")
                .setEphemeral(true).queue();
    }

    private void handleChangeBan(SlashCommandInteractionEvent event) {
        String steamidsInput = event.getOption("steamid").getAsString();
        String time = event.getOption("time").getAsString();
        String reason = event.getOption("reason").getAsString();
        String siteReason = event.getOption("site_reason").getAsString();

        // Разбиваем строку по пробелам на массив
        String[] steamidArray = steamidsInput.split("\\s+");
        List<String> validSteamIds = new ArrayList<>();

        // Проверяем каждый SteamID
        for (String id : steamidArray) {
            if (!id.matches("^765\\d{14}$")) {
                event.reply("❌ **Ошибка:** Неверный формат SteamID у `" + id + "`!\nОн должен состоять ровно из 17 цифр и начинаться с `765`.")
                        .setEphemeral(true).queue();
                return;
            }
            validSteamIds.add(id);
        }

        // Валидация времени
        if (!time.equals("0") && !time.toLowerCase().matches("^\\d+[dhm]$")) {
            event.reply("❌ **Ошибка:** Неверный формат времени!\nИспользуй числа и букву **d** (дни), **h** (часы) или **m** (минуты).\n*Примеры: 60d, 12h, 30m*")
                    .setEphemeral(true).queue();
            return;
        }

        TextChannel channel = event.getJDA().getTextChannelById(LOG_CHANNEL_ID);
        if (channel == null) {
            event.reply("❌ Не могу найти канал для логов. Проверь LOG_CHANNEL_ID на сервере.").setEphemeral(true).queue();
            return;
        }

        // Генерируем список ссылок на профили
        StringBuilder profiles = new StringBuilder();
        for (String s : validSteamIds) {
            profiles.append("[`").append(s).append("`](https://fearproject.ru/profile/").append(s).append(") ");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📋 Запрос на изменение бана")
                .setColor(Color.BLUE)
                .addField("Администратор (отправил):", event.getUser().getAsMention(), false)
                .addField("Профили Fear (" + validSteamIds.size() + " шт.):", profiles.toString(), false)
                .addField("Новое время:", time, true)
                .addField("Новая причина (на сайте):", siteReason, true)
                .addField("Текст запроса:", reason, false);

        channel.sendMessageEmbeds(embed.build())
                .addActionRow(
                        Button.success("accept_btn", "Принять"),
                        Button.danger("reject_btn", "Отклонить")
                ).queue(message -> {
                    activeRequests.put(message.getId(), new BanRequestData(validSteamIds, time, siteReason));
                });

        event.reply("✅ Запрос на " + validSteamIds.size() + " SteamID успешно отправлен!").setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        String messageId = event.getMessageId();

        if (buttonId.equals("accept_btn") || buttonId.equals("reject_btn") || buttonId.equals("retry_btn")) {
            BanRequestData requestData = activeRequests.get(messageId);

            if (requestData == null) {
                event.reply("❌ Данные этого запроса устарели или бот был перезагружен.")
                        .setEphemeral(true).queue();
                return;
            }

            // Отложенный ответ, так как API запросы будут идти один за другим
            event.deferEdit().queue(hook -> {
                // Временно отключаем кнопки, пока идет обработка, чтобы не кликали дважды
                hook.editOriginalComponents(ActionRow.of(
                        Button.success("accept_btn", "Обработка...").asDisabled(),
                        Button.danger("reject_btn", "Отклонить").asDisabled()
                )).queue();
            });

            EmbedBuilder updatedEmbed = new EmbedBuilder(event.getMessage().getEmbeds().get(0));

            // Очищаем старые поля "Результат", если это повторная попытка (Retry)
            List<MessageEmbed.Field> fields = new ArrayList<>(updatedEmbed.getFields());
            fields.removeIf(f -> f.getName() != null && f.getName().contains("Результат") || f.getName().contains("Решение"));
            updatedEmbed.clearFields();
            for (MessageEmbed.Field f : fields) updatedEmbed.addField(f);

            // Обработка "Отклонить"
            if (buttonId.equals("reject_btn")) {
                activeRequests.remove(messageId);
                updatedEmbed.setColor(Color.RED);
                updatedEmbed.addField("Решение", "❌ Отклонено администратором " + event.getUser().getAsMention(), false);
                event.getHook().editOriginalEmbeds(updatedEmbed.build())
                        .setComponents(ActionRow.of(
                                Button.success("accept_btn", "Принять").asDisabled(),
                                Button.danger("reject_btn", "Отклонить").asDisabled()
                        )).queue();
                return;
            }

            // Асинхронная поочередная обработка (Accept или Retry)
            CompletableFuture.supplyAsync(() -> {
                List<String> successIds = new ArrayList<>();
                List<String> failedIds = new ArrayList<>();
                StringBuilder errors = new StringBuilder();

                for (String id : requestData.steamids()) {
                    // Ждем выполнения API для конкретного ID
                    ApiResult res = updateBanApi(id, requestData.timeStr(), requestData.siteReason()).join();

                    if (res.success()) {
                        successIds.add(id);
                    } else {
                        failedIds.add(id);
                        errors.append("`").append(id).append("`: ").append(res.message()).append("\n");
                    }

                    // ЗАЩИТА ОТ ЛИМИТОВ API: Пауза 800 миллисекунд между запросами
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                }
                return new ProcessResult(successIds, failedIds, errors.toString());
            }).thenAccept(result -> {

                if (result.failedIds().isEmpty()) {
                    // ВСЁ УСПЕШНО
                    activeRequests.remove(messageId); // Удаляем из памяти, больше обрабатывать не нужно
                    updatedEmbed.setColor(Color.GREEN);
                    updatedEmbed.addField("Результат", "✅ Все SteamID успешно обработаны администратором " + event.getUser().getAsMention() + "!", false);

                    event.getHook().editOriginalEmbeds(updatedEmbed.build())
                            .setComponents(ActionRow.of(
                                    Button.success("accept_btn", "Принять").asDisabled(),
                                    Button.danger("reject_btn", "Отклонить").asDisabled()
                            )).queue();
                } else {
                    // ЕСТЬ ОШИБКИ (ЧАСТИЧНО ИЛИ ПОЛНОСТЬЮ)
                    // Обновляем список в памяти ТОЛЬКО на те ID, которые выдали ошибку
                    activeRequests.put(messageId, new BanRequestData(result.failedIds(), requestData.timeStr(), requestData.siteReason()));
                    updatedEmbed.setColor(Color.ORANGE);

                    StringBuilder resText = new StringBuilder();
                    resText.append("Обрабатывал: ").append(event.getUser().getAsMention()).append("\n\n");

                    if (!result.successIds().isEmpty()) {
                        resText.append("**Успешно изменены:**\n").append(String.join(", ", result.successIds())).append("\n\n");
                    }
                    resText.append("**Возникла ошибка API:**\n").append(result.errorMessage());

                    // Если текст слишком длинный, обрезаем
                    String resStr = resText.toString();
                    if (resStr.length() > 1024) resStr = resStr.substring(0, 1020) + "...";

                    updatedEmbed.addField("Результат (Требует внимания)", resStr, false);

                    // Добавляем кнопку Retry (Синюю)
                    event.getHook().editOriginalEmbeds(updatedEmbed.build())
                            .setComponents(ActionRow.of(
                                    Button.success("accept_btn", "Принять").asDisabled(),
                                    Button.danger("reject_btn", "Отклонить").asDisabled(),
                                    Button.primary("retry_btn", "Повторить ошибки (Retry) 🔄")
                            )).queue();
                }
            });
        }
    }

    // --- ФУНКЦИИ ДЛЯ РАБОТЫ С API САЙТА ---

    private long parseDurationToSeconds(String timeStr) {
        if (timeStr.equals("0")) return 0;
        Matcher m = Pattern.compile("^(\\d+)([dhm])$").matcher(timeStr.toLowerCase().trim());
        if (!m.matches()) return 0;

        long val = Long.parseLong(m.group(1));
        String unit = m.group(2);

        return switch (unit) {
            case "d" -> val * 86400;
            case "h" -> val * 3600;
            case "m" -> val * 60;
            default -> 0;
        };
    }

    private CompletableFuture<ApiResult> updateBanApi(String steamid, String timeStr, String siteReason) {
        if (SITE_ACCESS_TOKEN == null || SITE_ACCESS_TOKEN.isEmpty()) {
            return CompletableFuture.completedFuture(new ApiResult(false, "У бота нет токена сайта. Пропиши /cookieadmin"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. ИЩЕМ АКТИВНЫЙ БАН (GET)
                String searchUrl = String.format("https://api.fearproject.ru/punishments/search?q=%s&page=1&limit=10&type=1", steamid);

                HttpRequest searchReq = HttpRequest.newBuilder()
                        .uri(URI.create(searchUrl))
                        .header("Accept", "application/json")
                        .header("Origin", "https://fearproject.ru")
                        .header("Referer", "https://fearproject.ru/")
                        .header("Cookie", "access_token=" + SITE_ACCESS_TOKEN)
                        .GET()
                        .build();

                HttpResponse<String> searchResp = httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
                if (searchResp.statusCode() == 429) {
                    return new ApiResult(false, "Сработал лимит запросов к сайту (HTTP 429)");
                }
                if (searchResp.statusCode() != 200) {
                    return new ApiResult(false, "Ошибка поиска (HTTP " + searchResp.statusCode() + ")");
                }

                JSONObject data = new JSONObject(searchResp.body());
                if (!data.has("punishments")) {
                    return new ApiResult(false, "Банов не найдено.");
                }

                JSONArray items = data.getJSONArray("punishments");
                JSONObject activeBan = null;

                for (int i = 0; i < items.length(); i++) {
                    JSONObject p = items.getJSONObject(i);
                    if (p.optInt("status") == 1 && steamid.equals(p.optString("steamid"))) {
                        activeBan = p;
                        break;
                    }
                }

                if (activeBan == null) {
                    return new ApiResult(false, "Игрок не в бане.");
                }

                int pid = activeBan.getInt("id");
                String name = activeBan.optString("name", steamid);

                // 2. ОТПРАВЛЯЕМ ЗАПРОС НА ИЗМЕНЕНИЕ (PUT)
                JSONObject payload = new JSONObject();
                payload.put("name", name);
                payload.put("steamid", steamid);
                payload.put("reason", siteReason);
                payload.put("duration", parseDurationToSeconds(timeStr));
                payload.put("punish_type", 0); // 0 = Бан

                HttpRequest updateReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.fearproject.ru/punishments/update/" + pid))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Origin", "https://fearproject.ru")
                        .header("Referer", "https://fearproject.ru/")
                        .header("Cookie", "access_token=" + SITE_ACCESS_TOKEN)
                        .PUT(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> updateResp = httpClient.send(updateReq, HttpResponse.BodyHandlers.ofString());

                if (updateResp.statusCode() == 429) {
                    return new ApiResult(false, "Сработал лимит запросов к сайту (HTTP 429)");
                } else if (updateResp.statusCode() >= 200 && updateResp.statusCode() < 300) {
                    return new ApiResult(true, "Изменен (ID: " + pid + ")");
                } else {
                    return new ApiResult(false, "Ошибка изменения (HTTP " + updateResp.statusCode() + ")");
                }

            } catch (Exception e) {
                return new ApiResult(false, "Ошибка сети: " + e.getMessage());
            }
        });
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ (Record-ы) ---
    // Внимание: теперь храним List<String> steamids
    private record BanRequestData(List<String> steamids, String timeStr, String siteReason) {}
    private record ApiResult(boolean success, String message) {}
    private record ProcessResult(List<String> successIds, List<String> failedIds, String errorMessage) {}
}
