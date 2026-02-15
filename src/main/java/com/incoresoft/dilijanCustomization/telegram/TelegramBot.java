package com.incoresoft.dilijanCustomization.telegram;

import com.incoresoft.dilijanCustomization.domain.attendance.service.AttendanceReportService;
import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationReportService;
import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationStatusService;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemDto;
import com.incoresoft.dilijanCustomization.domain.shared.dto.ListItemsResponse;
import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "evacuation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TelegramBot extends TelegramLongPollingBot {

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    private final FaceApiRepository faceApiRepository;
    private final EvacuationReportService reportService;
    private final AttendanceReportService attendanceReportService;
    private final EvacuationStatusService evacuationStatusService;

    /** Выбранные списки на пользователя. */
    private final Map<Long, Set<Long>> chatSelections = new ConcurrentHashMap<>();
    /** Текущий режим для каждого чата. */
    private enum Mode { NONE, EVACUATION, ATTENDANCE }
    private final Map<Long, Mode> chatModes = new ConcurrentHashMap<>();

    private static final ZoneId KYIV_TZ = ZoneId.of("Asia/Yerevan");
    private static final DateTimeFormatter MM_DD_YYYY =
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);
    // Column indexes in the evacuation workbook exported by ReportService
    private static final int REPORT_COL_STATUS = 0;
    private static final int REPORT_COL_ID = 3;
    private static final int REPORT_COL_NAME = 4;
    private static final int LIST_ITEMS_PAGE_LIMIT = 1000;

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null) return;
        try {
            // Обработка загруженного пользователем документа: парсинг и обновление статусов
            if (update.getMessage() != null && update.getMessage().hasDocument()) {
                handleIncomingDocument(update);
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleIncomingText(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception ex) {
            log.error("Error processing update: {}", ex.getMessage(), ex);
        }
    }

    /** Обработка текстовых сообщений (в т.ч. /start, выбор режима, ввод даты) */
    private void handleIncomingText(Update update) throws TelegramApiException {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        if ("/start".equalsIgnoreCase(text)) {
            chatSelections.remove(chatId);
            chatModes.put(chatId, Mode.NONE);
            sendStartMenu(chatId);
            return;
        }
        if ("Evacuation".equalsIgnoreCase(text)) {
            chatModes.put(chatId, Mode.EVACUATION);
            sendListSelection(chatId);
            return;
        }
        if ("Attendance".equalsIgnoreCase(text)) {
            chatModes.put(chatId, Mode.ATTENDANCE);
            sendAttendanceMenu(chatId);
            return;
        }
        // Если режим ATTENDANCE, пробуем разобрать дату
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
        // Иначе выводим подсказку
        execute(new SendMessage(chatId.toString(), "Send /start to begin."));
    }

    /** Обработка callback-кнопок (переключение списков, генерация отчётов) */
    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        String data = callback.getData();
        if (data == null) return;

        Long chatId = callback.getMessage().getChat().getId();
        Integer messageId = callback.getMessage().getMessageId();

        if (data.startsWith("toggle_")) {
            try {
                Long listId = Long.valueOf(data.substring("toggle_".length()));
                chatSelections.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet());
                Set<Long> selected = chatSelections.get(chatId);
                if (selected.contains(listId)) selected.remove(listId);
                else selected.add(listId);
                editListSelection(chatId, messageId, selected);
            } catch (NumberFormatException ex) {
                log.warn("Invalid toggle callback data: {}", data);
            }
            return;
        }
        // Генерация отчёта по выбранным спискам
        if ("generate".equals(data)) {
            Set<Long> selected = chatSelections.getOrDefault(chatId, Collections.emptySet());
            if (selected.isEmpty()) {
                execute(new SendMessage(chatId.toString(), "No lists selected. Use the checkboxes to pick at least one list."));
                return;
            }
            Integer waitMessageId = null;
            long startNanos = System.nanoTime();
            try {
                waitMessageId = sendGeneratingMessage(chatId);
                File report = reportService.buildEvacuationReport(selected.stream().toList());
                SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
                doc.setCaption("Evacuation report for lists: " + selected.size());
                execute(doc);
                deleteMessageIfPresent(chatId, waitMessageId);
                sendGenerationTime(chatId, startNanos);
            } catch (Exception ex) {
                deleteMessageIfPresent(chatId, waitMessageId);
                log.error("Report generation failed: {}", ex.getMessage(), ex);
                execute(new SendMessage(chatId.toString(), "Failed to generate report: " + ex.getMessage()));
            }
            return;
        }
        // Генерация отчёта для всех подходящих списков
        if ("GEN_ALL".equals(data)) {
            try {
                handleGenerateForAll(chatId);
            } catch (Exception ex) {
                execute(new SendMessage(chatId.toString(), "Failed to generate report for all lists: " + ex.getMessage()));
            }
            return;
        }
        // Attendance: сегодня/вчера
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

    // Отправка стартового меню
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

    // ------ Меню для Evacuation ------
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

    private InlineKeyboardMarkup buildEvacuationKeyboard(List<FaceListDto> lists, Set<Long> selected) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (FaceListDto l : lists) {
            boolean isSelected = selected != null && selected.contains(l.getId());
            String name = l.getName() != null ? l.getName() : ("List " + l.getId());
            if (name.length() > 48) name = name.substring(0, 47) + "…";
            String text = (isSelected ? "✅ " : "☐ ") + name;

            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(text);
            btn.setCallbackData("toggle_" + l.getId());
            rows.add(List.of(btn));
        }
        InlineKeyboardButton gen = new InlineKeyboardButton();
        gen.setText("🧾 Generate Report");
        gen.setCallbackData("generate");
        rows.add(List.of(gen));

        InlineKeyboardButton genAll = new InlineKeyboardButton();
        genAll.setText("🚀 Generate for All Lists");
        genAll.setCallbackData("GEN_ALL");
        rows.add(List.of(genAll));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private List<FaceListDto> filterReportableLists(FaceListsResponse response) {
        if (response == null || response.getData() == null) return Collections.emptyList();
        return response.getData().stream()
                .filter(Objects::nonNull)
                .filter(l -> l.getStatus() != null && l.getStatus().equals(1))
                .filter(l -> l.getTimeAttendance() != null && Boolean.TRUE.equals(l.getTimeAttendance().getEnabled()))
                .sorted(Comparator.comparing(FaceListDto::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void handleGenerateForAll(Long chatId) throws Exception {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> eligible = filterReportableLists(response);
        if (eligible.isEmpty()) {
            execute(new SendMessage(chatId.toString(), "No lists with reports enabled."));
            return;
        }
        Integer waitMessageId = null;
        long startNanos = System.nanoTime();
        List<Long> allIds = eligible.stream().map(FaceListDto::getId).toList();
        try {
            waitMessageId = sendGeneratingMessage(chatId);
            File report = reportService.buildEvacuationReport(allIds);
            SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
            doc.setCaption("Evacuation report (ALL enabled lists): " + allIds.size() + " lists");
            execute(doc);
            deleteMessageIfPresent(chatId, waitMessageId);
            sendGenerationTime(chatId, startNanos);
        } catch (Exception ex) {
            deleteMessageIfPresent(chatId, waitMessageId);
            throw ex;
        }
    }

    // ------ Attendance ------
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
        kb.setKeyboard(List.of(List.of(today, yesterday)));

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setReplyMarkup(kb);
        execute(msg);
    }

    private void sendStarted(Long chatId) throws TelegramApiException {
        execute(new SendMessage(chatId.toString(), "Started Report Creation. Wait a minute."));
    }

    private Integer sendGeneratingMessage(Long chatId) throws TelegramApiException {
        Message message = execute(new SendMessage(chatId.toString(), "Generating evacuation report. Please wait🔄"));
        return message != null ? message.getMessageId() : null;
    }

    private void deleteMessageIfPresent(Long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        try {
            execute(new DeleteMessage(chatId.toString(), messageId));
        } catch (TelegramApiException ex) {
            log.warn("Failed to delete waiting message {}: {}", messageId, ex.getMessage());
        }
    }

    private void sendGenerationTime(Long chatId, long startNanos) throws TelegramApiException {
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        execute(new SendMessage(chatId.toString(), "Generation took: " + formatDuration(duration)));
    }

    private String formatDuration(Duration duration) {
        long totalMillis = duration.toMillis();
        long minutes = totalMillis / 60000;
        long seconds = (totalMillis % 60000) / 1000;
        long millis = totalMillis % 1000;
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%d.%03ds", seconds, millis);
    }

    private void generateAttendanceForDate(Long chatId, LocalDate date) throws Exception {
        File report = attendanceReportService.buildSingleDayReport(date);
        SendDocument doc = new SendDocument(chatId.toString(), new InputFile(report));
        doc.setCaption("Attendance report for " + date.format(MM_DD_YYYY));
        execute(doc);
    }

    /**
     * Обрабатывает загруженный отчёт об эвакуации. Парсит XLSX и обновляет статусы.
     */
    private void handleIncomingDocument(Update update) {
        var msg = update.getMessage();
        if (msg == null || msg.getDocument() == null) return;
        Long chatId = msg.getChatId();
        try {
            org.telegram.telegrambots.meta.api.objects.Document doc = msg.getDocument();
            org.telegram.telegrambots.meta.api.methods.GetFile getFile = new org.telegram.telegrambots.meta.api.methods.GetFile();
            getFile.setFileId(doc.getFileId());
            org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
            java.io.File tmp = downloadFile(tgFile);
            int updatesCount = 0;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tmp)) {
                Workbook wb = WorkbookFactory.create(fis);
                // Соотнесение названий листов и ID списков (по sanitize)
                Map<String, Long> nameToId = new HashMap<>();
                var listsResponse = faceApiRepository.getFaceLists(200);
                var lists = filterReportableLists(listsResponse);
                for (var list : lists) {
                    String sheetName = sanitizeSheetName(list.getName() != null ? list.getName() : ("List_" + list.getId()));
                    nameToId.put(sheetName, list.getId());
                }
                Map<Long, Map<String, Long>> itemNameMappings = buildListItemNameMappings(nameToId.values());
                List<EvacuationUpdate> updates = extractUpdatesFromWorkbook(wb, nameToId, itemNameMappings);
                for (EvacuationUpdate upd : updates) {
                    evacuationStatusService.updateStatus(upd.listId(), upd.listItemId(), upd.status());
                }
                updatesCount = updates.size();
            } finally {
                if (tmp != null && tmp.exists()) tmp.delete();
            }
            // подтверждение
            String confirmation = String.format("Updated %d evacuation status records.", updatesCount);
            execute(new SendMessage(chatId.toString(), confirmation));
        } catch (Exception ex) {
            log.error("Failed to process uploaded evacuation report: {}", ex.getMessage(), ex);
            try {
                execute(new SendMessage(chatId.toString(), "Failed to process evacuation report: " + ex.getMessage()));
            } catch (TelegramApiException e) {
                log.error("Failed to send error message to user: {}", e.getMessage(), e);
            }
        }
    }

    // Удаляет недопустимые символы и обрезает имя до 31 символа
    private static String sanitizeSheetName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[:\\\\/*?\\[\\]]", "_");
        return cleaned.length() <= 31 ? cleaned : cleaned.substring(0, 31);
    }

    private LocalDate parseMmDdYyyy(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw.trim(), MM_DD_YYYY);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Extract updates from the uploaded evacuation workbook. The workbook layout must match
     * {@link com.incoresoft.dilijanCustomization.domain.shared.service.ReportService#exportEvacuationWorkbook},
     * where the list item ID is stored in column 3.
     */
    static List<EvacuationUpdate> extractUpdatesFromWorkbook(Workbook wb, Map<String, Long> nameToId) {
        return extractUpdatesFromWorkbook(wb, nameToId, Collections.emptyMap());
    }

    static List<EvacuationUpdate> extractUpdatesFromWorkbook(Workbook wb,
                                                             Map<String, Long> nameToId,
                                                             Map<Long, Map<String, Long>> listItemNameMappings) {
        List<EvacuationUpdate> updates = new ArrayList<>();
        if (wb == null || nameToId == null || nameToId.isEmpty()) {
            return updates;
        }

        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            Long listId = nameToId.get(sheet.getSheetName());
            if (listId == null) continue;
            Map<String, Long> listNameToId = listItemNameMappings.getOrDefault(listId, Collections.emptyMap());

            int last = sheet.getLastRowNum();
            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Boolean status = parseStatus(row.getCell(REPORT_COL_STATUS));
                Long listItemId = parseListItemId(row.getCell(REPORT_COL_ID));
                if (listItemId == null) {
                    listItemId = resolveListItemIdByName(row.getCell(REPORT_COL_NAME), listNameToId);
                }
                if (status != null && listItemId != null) {
                    updates.add(new EvacuationUpdate(listId, listItemId, status));
                }
            }
        }
        return updates;
    }

    private Map<Long, Map<String, Long>> buildListItemNameMappings(Iterable<Long> listIds) {
        Map<Long, Map<String, Long>> mappings = new HashMap<>();
        for (Long listId : listIds) {
            if (listId == null) {
                continue;
            }

            Map<String, Long> itemNameToId = new HashMap<>();
            int offset = 0;
            while (true) {
                ListItemsResponse response = faceApiRepository.getListItems(
                        listId,
                        "",
                        "",
                        offset,
                        LIST_ITEMS_PAGE_LIMIT,
                        "asc",
                        "name");
                List<ListItemDto> items = (response == null || response.getData() == null)
                        ? Collections.emptyList()
                        : response.getData();

                for (ListItemDto item : items) {
                    if (item == null || item.getId() == null || item.getName() == null || item.getName().isBlank()) {
                        continue;
                    }
                    itemNameToId.putIfAbsent(item.getName().trim(), item.getId());
                }

                if (items.size() < LIST_ITEMS_PAGE_LIMIT) {
                    break;
                }
                offset += LIST_ITEMS_PAGE_LIMIT;
            }

            mappings.put(listId, itemNameToId);
        }
        return mappings;
    }

    private static Long resolveListItemIdByName(Cell nameCell, Map<String, Long> listNameToId) {
        if (nameCell == null || listNameToId == null || listNameToId.isEmpty()) {
            return null;
        }
        String raw = switch (nameCell.getCellType()) {
            case STRING -> nameCell.getStringCellValue();
            default -> nameCell.toString();
        };
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return listNameToId.get(raw.trim());
    }

    private static Boolean parseStatus(Cell statusCell) {
        if (statusCell == null) return null;
        return switch (statusCell.getCellType()) {
            case BOOLEAN -> statusCell.getBooleanCellValue();
            case NUMERIC -> statusCell.getNumericCellValue() != 0;
            case STRING -> parseStatusString(statusCell.getStringCellValue());
            default -> parseStatusString(statusCell.toString());
        };
    }

    private static Boolean parseStatusString(String statusRaw) {
        if (statusRaw == null) {
            return null;
        }
        String trimmed = statusRaw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if ("Evacuated".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)
                || "☐".equals(trimmed)) {
            return false;
        }
        if ("On site".equalsIgnoreCase(trimmed) || "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)
                || "☑".equals(trimmed)) {
            return true;
        }
        return true;
    }

    private static Long parseListItemId(Cell idCell) {
        if (idCell == null) return null;
        return switch (idCell.getCellType()) {
            case NUMERIC -> (long) idCell.getNumericCellValue();
            case STRING -> parseLong(idCell.getStringCellValue());
            default -> parseLong(idCell.toString());
        };
    }

    private static Long parseLong(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record EvacuationUpdate(Long listId, Long listItemId, boolean status) {}

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public String getBotToken() { return botToken; }
}
