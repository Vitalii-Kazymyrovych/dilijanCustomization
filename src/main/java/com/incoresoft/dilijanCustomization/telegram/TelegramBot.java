package com.incoresoft.dilijanCustomization.telegram;

import com.incoresoft.dilijanCustomization.domain.attendance.service.AttendanceReportService;
import com.incoresoft.dilijanCustomization.domain.evacuation.EvacuationReportService;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    private final FaceApiRepository faceApiRepository;
    private final EvacuationReportService reportService;
    private final AttendanceReportService attendanceReportService; // Attendance reports

    /** Per-chat “selected lists” for evacuation. */
    private final Map<Long, Set<Long>> chatSelections = new ConcurrentHashMap<>();

    /** Simple per-chat mode switching. */
    private enum Mode { NONE, EVACUATION, ATTENDANCE }
    private final Map<Long, Mode> chatModes = new ConcurrentHashMap<>();

    private static final ZoneId KYIV_TZ = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter MM_DD_YYYY =
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null) return;
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleIncomingText(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception ex) {
            log.error("Error processing update: {}", ex.getMessage(), ex);
        }
    }

    /** Handle plain text messages (including /start, mode buttons, and attendance date input). */
    private void handleIncomingText(Update update) throws TelegramApiException {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        if ("/start".equalsIgnoreCase(text)) {
            chatSelections.remove(chatId);
            chatModes.put(chatId, Mode.NONE);
            sendStartMenu(chatId);
            return;
        }

        // Start menu buttons (ReplyKeyboard)
        if ("Evacuation".equalsIgnoreCase(text)) {
            chatModes.put(chatId, Mode.EVACUATION);
            sendListSelection(chatId); // existing evacuation menu
            return;
        }
        if ("Attendance".equalsIgnoreCase(text)) {
            chatModes.put(chatId, Mode.ATTENDANCE);
            sendAttendanceMenu(chatId);
            return;
        }

        // While in ATTENDANCE mode: interpret text as date (ONLY MM/DD/YYYY), else hint.
        if (chatModes.getOrDefault(chatId, Mode.NONE) == Mode.ATTENDANCE) {
            LocalDate parsed = parseMmDdYyyy(text);
            if (parsed != null) {
                try {
                    sendStarted(chatId);
                    generateAttendanceForDate(chatId, parsed);
                } catch (Exception ex) {
                    log.error("Attendance report error: {}", ex.getMessage(), ex);
                    execute(new SendMessage(chatId.toString(), "Failed to create attendance report: " + ex.getMessage()));
                }
            } else {
                String hint = """
                        Please enter a date in this format:
                        MM/DD/YYYY (e.g., 11/10/2025)
                        Or use the buttons below: Today / Yesterday.
                        """;
                execute(new SendMessage(chatId.toString(), hint));
            }
            return;
        }

        // Fallback help
        execute(new SendMessage(chatId.toString(), "Send /start to begin."));
    }

    /** Handle all callback buttons (evacuation + attendance). */
    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        String data = callback.getData();
        if (data == null) return;

        Long chatId = callback.getMessage().getChat().getId();
        Integer messageId = callback.getMessage().getMessageId();

        if (data.startsWith("toggle_")) {
            // Evacuation list toggle
            try {
                Long listId = Long.valueOf(data.substring("toggle_".length()));
                chatSelections.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet());
                Set<Long> selected = chatSelections.get(chatId);
                if (selected.contains(listId)) selected.remove(listId); else selected.add(listId);
                editListSelection(chatId, messageId, selected);
            } catch (NumberFormatException ex) {
                log.warn("Invalid toggle callback data: {}", data);
            }
            return;
        }

        // Evacuation: generate for selected
        if ("generate".equals(data)) {
            Set<Long> selected = chatSelections.getOrDefault(chatId, Collections.emptySet());
            if (selected.isEmpty()) {
                execute(new SendMessage(chatId.toString(), "No lists selected. Use the checkboxes to pick at least one list."));
                return;
            }
            try {
                File report = reportService.buildEvacuationReport(selected.stream().toList());
                SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
                doc.setCaption("Evacuation report for lists: " + selected.size());
                execute(doc);
            } catch (Exception ex) {
                log.error("Report generation failed: {}", ex.getMessage(), ex);
                execute(new SendMessage(chatId.toString(), "Failed to generate report: " + ex.getMessage()));
            }
            return;
        }

        // Evacuation: generate for ALL eligible lists
        if ("GEN_ALL".equals(data)) {
            try {
                handleGenerateForAll(chatId);
            } catch (Exception ex) {
                execute(new SendMessage(chatId.toString(), "Failed to generate report for all lists: " + ex.getMessage()));
            }
            return;
        }

        // Attendance: today / yesterday
        if ("ATT_TODAY".equals(data)) {
            try {
                sendStarted(chatId);
                generateAttendanceForDate(chatId, LocalDate.now(KYIV_TZ));
            } catch (Exception ex) {
                log.error("Attendance (today) failed: {}", ex.getMessage(), ex);
                execute(new SendMessage(chatId.toString(), "Failed to create attendance report: " + ex.getMessage()));
            }
            return;
        }
        if ("ATT_YESTERDAY".equals(data)) {
            try {
                sendStarted(chatId);
                generateAttendanceForDate(chatId, LocalDate.now(KYIV_TZ).minusDays(1));
            } catch (Exception ex) {
                log.error("Attendance (yesterday) failed: {}", ex.getMessage(), ex);
                execute(new SendMessage(chatId.toString(), "Failed to create attendance report: " + ex.getMessage()));
            }
        }
    }

    /** Sends the /start menu with two horizontal buttons: Evacuation | Attendance. */
    private void sendStartMenu(Long chatId) throws TelegramApiException {
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setResizeKeyboard(true);
        kb.setSelective(true);
        kb.setOneTimeKeyboard(false);

        KeyboardRow row = new KeyboardRow();
        row.add("Evacuation");
        row.add("Attendance");
        kb.setKeyboard(List.of(row));

        SendMessage msg = new SendMessage(chatId.toString(), "Choose a mode:");
        msg.setReplyMarkup(kb);
        execute(msg);
    }

    /* ========================= Evacuation UI ========================= */

    /** Show filtered lists (reports enabled + status==1) with toggle, Generate, and Generate All. */
    private void sendListSelection(Long chatId) throws TelegramApiException {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> lists = filterReportableLists(response);

        if (lists.isEmpty()) {
            execute(new SendMessage(chatId.toString(), "No lists available to report on."));
            return;
        }

        SendMessage msg = new SendMessage(chatId.toString(), "Select lists for the evacuation report:");
        InlineKeyboardMarkup keyboard = buildEvacuationKeyboard(lists, chatSelections.getOrDefault(chatId, Collections.emptySet()));
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    /** Rebuild inline keyboard on toggle to reflect current selection. */
    private void editListSelection(Long chatId, Integer messageId, Set<Long> selected) throws TelegramApiException {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> lists = filterReportableLists(response);
        if (lists.isEmpty()) return;

        InlineKeyboardMarkup keyboard = buildEvacuationKeyboard(lists, selected);
        EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setReplyMarkup(keyboard);
        execute(edit);
    }

    /** Build evacuation keyboard: per-list toggles, Generate, and (last) Generate for All Lists. */
    private InlineKeyboardMarkup buildEvacuationKeyboard(List<FaceListDto> lists, Set<Long> selected) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (FaceListDto l : lists) {
            boolean isSelected = selected != null && selected.contains(l.getId());
            String name = l.getName() != null ? l.getName() : ("List " + l.getId());
            if (name.length() > 48) name = name.substring(0, 47) + "…";
            String text = (isSelected ? "✅ " : "☐ ") + name;

            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(text);
            btn.setCallbackData("toggle_" + l.getId()); // matches handler
            rows.add(List.of(btn));
        }

        // Generate for selected
        InlineKeyboardButton gen = new InlineKeyboardButton();
        gen.setText("🧾 Generate Report");
        gen.setCallbackData("generate");
        rows.add(List.of(gen));

        // LAST: Generate for ALL enabled lists
        InlineKeyboardButton genAll = new InlineKeyboardButton();
        genAll.setText("🚀 Generate for All Lists");
        genAll.setCallbackData("GEN_ALL");
        rows.add(List.of(genAll));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    /** Shared filter used in menus and All-Lists generation. */
    private List<FaceListDto> filterReportableLists(FaceListsResponse response) {
        if (response == null || response.getData() == null) return Collections.emptyList();
        return response.getData().stream()
                .filter(Objects::nonNull)
                .filter(l -> l.getStatus() != null && l.getStatus().equals(1))
                .filter(l -> l.getTimeAttendance() != null && Boolean.TRUE.equals(l.getTimeAttendance().getEnabled()))
                .sorted(Comparator.comparing(FaceListDto::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Generate evacuation report for ALL eligible lists. */
    private void handleGenerateForAll(Long chatId) throws Exception {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> eligible = filterReportableLists(response);

        if (eligible.isEmpty()) {
            execute(new SendMessage(chatId.toString(), "No lists with reports enabled."));
            return;
        }

        List<Long> allIds = eligible.stream().map(FaceListDto::getId).toList();
        File report = reportService.buildEvacuationReport(allIds);

        SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
        doc.setCaption("Evacuation report (ALL enabled lists): " + allIds.size() + " lists");
        execute(doc);
    }

    /* ========================= Attendance UI ========================= */

    /** Attendance entry message + inline buttons Today / Yesterday and text instructions. */
    private void sendAttendanceMenu(Long chatId) throws TelegramApiException {
        String text = """
                Attendance report:
                • Tap a button for Today or Yesterday
                • Or type a date in this format:
                  MM/DD/YYYY (e.g., 11/10/2025)
                """;

        InlineKeyboardButton today = new InlineKeyboardButton();
        today.setText("Today");
        today.setCallbackData("ATT_TODAY");

        InlineKeyboardButton yesterday = new InlineKeyboardButton();
        yesterday.setText("Yesterday");
        yesterday.setCallbackData("ATT_YESTERDAY");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(today, yesterday))); // horizontal row

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setReplyMarkup(kb);
        execute(msg);
    }

    /** Small helper: send a "started" notice before building attendance report. */
    private void sendStarted(Long chatId) throws TelegramApiException {
        execute(new SendMessage(chatId.toString(), "Started Report Creation. Wait a minute."));
    }

    /** Generate attendance (cafeteria) report for a given date and send it. */
    private void generateAttendanceForDate(Long chatId, LocalDate date) throws Exception {
        // Adjust if your AttendanceReportService signature differs.
        File report = attendanceReportService.buildSingleDayReport(date);

        SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
        doc.setCaption("Attendance report for " + date.format(MM_DD_YYYY));
        execute(doc);
    }

    /** Accept ONLY MM/DD/YYYY. */
    private LocalDate parseMmDdYyyy(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            return LocalDate.parse(s, MM_DD_YYYY);
        } catch (Exception ignored) {
            return null;
        }
    }

    /* ========================= Bot identity ========================= */

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
}
