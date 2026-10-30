// AI Passport: resident Bluetooth multi-firmware installer.
#include "app_ble.h"
#include "app_firmware.h"
#include "bsp_button.h"
#include "bsp_display.h"
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "nvs_flash.h"
#include <stdio.h>
#include <string.h>

extern const lv_font_t font_chinese_16;

typedef enum {
    ACTION_NONE = 0,
    ACTION_SELECT,
    ACTION_CLEAR,
    ACTION_BOOT,
} pending_action_t;

static const char *TAG = "passport_installer";
static lv_obj_t *s_status_label;
static lv_obj_t *s_detail_label;
static lv_obj_t *s_slots_label;
static lv_obj_t *s_progress;
static volatile pending_action_t s_pending_action;
static volatile uint8_t s_action_slot;
static uint8_t s_cursor_slot;
static bool s_clear_confirmation;
static TickType_t s_clear_deadline;

static lv_obj_t *create_label(lv_obj_t *parent, const char *text, int x, int y,
                              int width, int height, uint32_t color,
                              lv_text_align_t align)
{
    lv_obj_t *label = lv_label_create(parent);
    lv_label_set_text(label, text);
    lv_label_set_long_mode(label, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(label, x, y);
    lv_obj_set_size(label, width, height);
    lv_obj_set_style_text_font(label, &font_chinese_16, 0);
    lv_obj_set_style_text_color(label, lv_color_hex(color), 0);
    lv_obj_set_style_text_align(label, align, 0);
    return label;
}

static void build_ui(void)
{
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x07120F), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);
    lv_obj_set_scrollable(screen, false);

    create_label(screen, "Play Library", 12, 12, 216, 28,
                 0xE8FFF8, LV_TEXT_ALIGN_CENTER);
    s_status_label = create_label(screen, "Starting", 12, 48, 216, 28,
                                  0x48D5B5, LV_TEXT_ALIGN_CENTER);
    s_detail_label = create_label(screen, "", 12, 80, 216, 50,
                                  0xA7C2BA, LV_TEXT_ALIGN_CENTER);

    s_progress = lv_bar_create(screen);
    lv_obj_set_pos(s_progress, 18, 136);
    lv_obj_set_size(s_progress, 204, 10);
    lv_bar_set_range(s_progress, 0, 100);
    lv_obj_set_style_bg_color(s_progress, lv_color_hex(0x1C3731), LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_progress, lv_color_hex(0x48D5B5), LV_PART_INDICATOR);

    s_slots_label = create_label(screen, "", 14, 158, 212, 104,
                                 0xE8FFF8, LV_TEXT_ALIGN_LEFT);
    create_label(screen, "Up/Down: select | Hold OK: play", 8, 286, 224, 24,
                 0x789B92, LV_TEXT_ALIGN_CENTER);
}

static void format_size(char *output, size_t output_size, size_t bytes)
{
    if (bytes >= 1024 * 1024) {
        snprintf(output, output_size, "%u.%02uM",
                 (unsigned)(bytes / (1024 * 1024)),
                 (unsigned)((bytes % (1024 * 1024)) * 100 / (1024 * 1024)));
    } else {
        snprintf(output, output_size, "%uK", (unsigned)(bytes / 1024));
    }
}

static void refresh_ui(void)
{
    char status[64];
    char detail[128];
    char slots[360];
    size_t used = 0;
    slots[0] = 0;
    uint32_t passkey = app_ble_passkey();
    app_firmware_state_t state = app_firmware_state();

    if (passkey != UINT32_MAX) {
        snprintf(status, sizeof(status), "Pair code %06lu", (unsigned long)passkey);
        snprintf(detail, sizeof(detail), "Confirm this code on phone");
    } else if (state == APP_FIRMWARE_RECEIVING) {
        snprintf(status, sizeof(status), "Installing play %u", app_firmware_active_slot() + 1);
        snprintf(detail, sizeof(detail), "Keep phone nearby and power on");
    } else if (s_clear_confirmation) {
        snprintf(status, sizeof(status), "Delete play %u?", s_cursor_slot + 1);
        snprintf(detail, sizeof(detail), "OK: delete | Other key: cancel");
    } else if (state == APP_FIRMWARE_ERROR) {
        snprintf(status, sizeof(status), "Operation failed");
        snprintf(detail, sizeof(detail), "Error 0x%lx. Check phone.",
                 (unsigned long)app_firmware_last_error());
    } else if (app_ble_authenticated()) {
        snprintf(status, sizeof(status), "Bluetooth connected");
        snprintf(detail, sizeof(detail), "Install plays from phone");
    } else if (app_ble_connected()) {
        snprintf(status, sizeof(status), "Pairing");
        snprintf(detail, sizeof(detail), "Complete secure pairing");
    } else {
        snprintf(status, sizeof(status), "Waiting for phone");
        snprintf(detail, sizeof(detail), "Open app: PassportNotify");
    }

    app_firmware_slot_info_t cursor_info;
    if (!app_firmware_slot_info(s_cursor_slot, &cursor_info) || !cursor_info.present) {
        uint8_t selected = app_firmware_selected_slot();
        if (selected < APP_FIRMWARE_MAX_SLOTS) s_cursor_slot = selected;
    }
    unsigned shown = 0;
    for (unsigned step = 0; step < app_firmware_slot_count(); ++step) {
        uint8_t slot = (s_cursor_slot + step) % APP_FIRMWARE_MAX_SLOTS;
        app_firmware_slot_info_t info;
        if (!app_firmware_slot_info(slot, &info) || !info.present) continue;
        if (shown++ == 2) break;
        char capacity[16];
        char size[16];
        format_size(capacity, sizeof(capacity), info.capacity);
        format_size(size, sizeof(size), info.image_size);
        int count;
        if (info.present) {
            count = snprintf(slots + used, sizeof(slots) - used,
                             "%s #%u  %s / %s\n   %s\n",
                             slot == s_cursor_slot ? ">" : " ", slot + 1,
                             size, capacity, info.name);
        } else {
            count = snprintf(slots + used, sizeof(slots) - used,
                             "%s #%u  Empty / %s\n",
                             slot == s_cursor_slot ? ">" : " ", slot + 1, capacity);
        }
        if (count < 0 || (size_t)count >= sizeof(slots) - used) break;
        used += (size_t)count;
    }
    if (!shown) used = snprintf(slots, sizeof(slots), "No plays yet.\nInstall from phone.\n");
    char total_free[16];
    char largest[16];
    format_size(total_free, sizeof(total_free), app_firmware_total_free());
    format_size(largest, sizeof(largest), app_firmware_largest_free_slot());
    snprintf(slots + used, sizeof(slots) - used, "Free %s | Largest %s",
             total_free, largest);

    lv_label_set_text(s_status_label, status);
    lv_label_set_text(s_detail_label, detail);
    lv_label_set_text(s_slots_label, slots);
    lv_bar_set_value(s_progress,
                     state == APP_FIRMWARE_RECEIVING ? app_firmware_progress() : 0,
                     LV_ANIM_OFF);
    lv_obj_set_hidden(s_progress, state != APP_FIRMWARE_RECEIVING);
}

static void queue_action(pending_action_t action, uint8_t slot)
{
    if (s_pending_action != ACTION_NONE) return;
    s_action_slot = slot;
    s_pending_action = action;
}

static void on_key(bsp_btn_t button, bsp_btn_ev_t event, void *user)
{
    (void)user;
    if (app_firmware_busy()) return;

    if (s_clear_confirmation) {
        if (event == BSP_BTN_CLICK && button == BSP_BTN_OK) {
            queue_action(ACTION_CLEAR, s_cursor_slot);
        } else if (event == BSP_BTN_CLICK || event == BSP_BTN_LONG) {
            s_clear_confirmation = false;
        }
        return;
    }

    if (event == BSP_BTN_CLICK && (button == BSP_BTN_UP || button == BSP_BTN_DOWN)) {
        int delta = button == BSP_BTN_UP ? -1 : 1;
        for (unsigned attempt = 0; attempt < APP_FIRMWARE_MAX_SLOTS; ++attempt) {
            s_cursor_slot = (uint8_t)((s_cursor_slot + APP_FIRMWARE_MAX_SLOTS + delta) % APP_FIRMWARE_MAX_SLOTS);
            app_firmware_slot_info_t info;
            if (app_firmware_slot_info(s_cursor_slot, &info) && info.present) {
                queue_action(ACTION_SELECT, s_cursor_slot);
                break;
            }
        }
    } else if (event == BSP_BTN_LONG && button == BSP_BTN_OK) {
        queue_action(ACTION_BOOT, s_cursor_slot);
    } else if (event == BSP_BTN_LONG && button == BSP_BTN_DOWN) {
        app_firmware_slot_info_t info;
        if (app_firmware_slot_info(s_cursor_slot, &info) && info.present) {
            s_clear_confirmation = true;
            s_clear_deadline = xTaskGetTickCount() + pdMS_TO_TICKS(5000);
        }
    }
}

static void handle_pending_action(void)
{
    pending_action_t action = s_pending_action;
    uint8_t slot = s_action_slot;
    s_pending_action = ACTION_NONE;
    if (action == ACTION_NONE) return;

    esp_err_t error = ESP_OK;
    if (action == ACTION_SELECT) {
        error = app_firmware_select(slot);
    } else if (action == ACTION_CLEAR) {
        s_clear_confirmation = false;
        error = app_firmware_clear(slot);
    } else if (action == ACTION_BOOT) {
        error = app_firmware_prepare_boot(slot);
        if (error == ESP_OK) {
            vTaskDelay(pdMS_TO_TICKS(300));
            esp_restart();
        }
    }
    if (error != ESP_OK) ESP_LOGW(TAG, "action %d failed: %s", action, esp_err_to_name(error));
}

void app_main(void)
{
    esp_err_t error = nvs_flash_init();
    ESP_ERROR_CHECK(error);

    app_firmware_init();
    uint8_t selected = app_firmware_selected_slot();
    s_cursor_slot = selected < APP_FIRMWARE_MAX_SLOTS ? selected : 0;

    ESP_ERROR_CHECK(bsp_display_init());
    ESP_ERROR_CHECK(bsp_lvgl_init() ? ESP_OK : ESP_FAIL);
    bsp_display_backlight(80);
    if (bsp_lvgl_lock(1000)) {
        build_ui();
        refresh_ui();
        bsp_lvgl_unlock();
    }

    ESP_ERROR_CHECK(bsp_button_init(on_key, NULL));
    ESP_ERROR_CHECK(app_ble_start());

    while (true) {
        handle_pending_action();
        if (s_clear_confirmation &&
            (int32_t)(xTaskGetTickCount() - s_clear_deadline) >= 0) {
            s_clear_confirmation = false;
        }
        if (bsp_lvgl_lock(100)) {
            refresh_ui();
            bsp_lvgl_unlock();
        }
        vTaskDelay(pdMS_TO_TICKS(150));
    }
}
