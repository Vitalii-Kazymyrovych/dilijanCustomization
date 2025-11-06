package com.incoresoft.dilijanCustomization.telegram;

import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListDto;
    import com.incoresoft.dilijanCustomization.domain.shared.dto.FaceListsResponse;
import com.incoresoft.dilijanCustomization.domain.evacuation.service.EvacuationReportService;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple Telegram bot that allows users to generate evacuation reports
 * directly from the chat interface.  Upon receiving the /start command the
 * bot presents a list of lists returned by VEZHA as checkboxes.  Users can
 * toggle individual lists on or off using inline buttons and then press
 * "Generate Report" to build the report.  The resulting Excel file is
 * delivered back to the requesting chat.
 *
 * Note: this bot relies on the Telegram Bots API library.  Be sure to add
 * the appropriate dependency to your build file (see the project guide).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvacuationBot extends TelegramLongPollingBot {
    @Value("${telegram.bot.username}")
    private String botUsername;
    @Value("${telegram.bot.token}")
    private String botToken;
    // Repositories and services
    private final FaceApiRepository faceApiRepository;
    private final EvacuationReportService reportService;
    // Maintain per chat selection state
    private final Map<Long, Set<Long>> chatSelections = new ConcurrentHashMap<>();

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

    private void handleIncomingText(Update update) throws TelegramApiException {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();
        if ("/start".equalsIgnoreCase(text)) {
            // Reset selection and send lists
            chatSelections.remove(chatId);
            sendListSelection(chatId);
        } else {
            // For any other message provide basic help
            SendMessage msg = new SendMessage(chatId.toString(), "Send /start to begin generating an evacuation report.");
            execute(msg);
        }
    }

    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        String data = callback.getData();
        Long chatId = callback.getMessage().getChat().getId();
        Integer messageId = callback.getMessage().getMessageId();
        if (data == null) return;
        if (data.startsWith("toggle_")) {
            // Extract list ID and toggle selection
            try {
                Long listId = Long.valueOf(data.substring("toggle_".length()));
                chatSelections.computeIfAbsent(chatId, k -> new HashSet<>());
                Set<Long> selected = chatSelections.get(chatId);
                if (selected.contains(listId)) {
                    selected.remove(listId);
                } else {
                    selected.add(listId);
                }
                // Update keyboard to reflect new state
                editListSelection(chatId, messageId, selected);
            } catch (NumberFormatException ex) {
                log.warn("Invalid toggle callback data: {}", data);
            }
        } else if ("generate".equals(data)) {
            // Generate report for selected lists
            Set<Long> selected = chatSelections.getOrDefault(chatId, Collections.emptySet());
            if (selected.isEmpty()) {
                SendMessage msg = new SendMessage(chatId.toString(), "No lists selected. Use the checkboxes to pick at least one list.");
                execute(msg);
                return;
            }
            // Build report and send as a document
            try {
                File report = reportService.buildEvacuationReport(selected.stream().toList());
                InputFile inputFile = new InputFile(report);
                SendDocument doc = new SendDocument();
                doc.setChatId(chatId.toString());
                doc.setDocument(inputFile);
                doc.setCaption("Evacuation report for lists: " + selected);
                execute(doc);
            } catch (Exception ex) {
                log.error("Report generation failed: {}", ex.getMessage(), ex);
                SendMessage err = new SendMessage(chatId.toString(), "Failed to generate report: " + ex.getMessage());
                execute(err);
            }
        }
    }

    /**
     * Send an initial message to the user containing a list of available
     * face lists as inline buttons.  Each button toggles the inclusion of
     * its list in the report.  A "Generate Report" button appears at the
     * bottom to initiate report creation once the user has finished
     * selecting.
     */
    private void sendListSelection(Long chatId) throws TelegramApiException {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> lists = response != null ? response.getData() : null;
        if (lists == null || lists.isEmpty()) {
            SendMessage msg = new SendMessage(chatId.toString(), "No lists available to report on.");
            execute(msg);
            return;
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("Select lists for the evacuation report:");
        InlineKeyboardMarkup keyboard = buildKeyboard(lists, chatSelections.getOrDefault(chatId, Collections.emptySet()));
        msg.setReplyMarkup(keyboard);
        execute(msg);
    }

    /**
     * Update the inline keyboard of an existing message to reflect the
     * currently selected lists.  This avoids sending a new message on each
     * toggle.
     */
    private void editListSelection(Long chatId, Integer messageId, Set<Long> selected) throws TelegramApiException {
        FaceListsResponse response = faceApiRepository.getFaceLists(200);
        List<FaceListDto> lists = response != null ? response.getData() : null;
        if (lists == null) return;
        InlineKeyboardMarkup keyboard = buildKeyboard(lists, selected);
        EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setReplyMarkup(keyboard);
        execute(edit);
    }

    /**
     * Build an inline keyboard for the supplied lists.  Selected lists are
     * prefixed with a checkmark to provide a visual cue.  A final row
     * contains the "Generate Report" button.
     */
    private InlineKeyboardMarkup buildKeyboard(List<FaceListDto> lists, Set<Long> selected) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        // Build one button per list
        for (FaceListDto list : lists) {
            InlineKeyboardButton btn = new InlineKeyboardButton();
            boolean isSelected = selected != null && selected.contains(list.getId());
            // Prefix selected lists with a green check; otherwise leave box empty
            String prefix = isSelected ? "✅ " : "☐ ";
            btn.setText(prefix + list.getName());
            btn.setCallbackData("toggle_" + list.getId());
            rows.add(Collections.singletonList(btn));
        }
        // Generate button
        InlineKeyboardButton gen = new InlineKeyboardButton();
        gen.setText("Generate Report");
        gen.setCallbackData("generate");
        rows.add(Collections.singletonList(gen));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
}
