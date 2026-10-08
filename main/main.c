// AI Passport: private Android notifications and a persistent firmware portal.
#include "app_alert.h"
#include "app_ble.h"
#include "app_firmware.h"
#include "bsp_audio.h"
#include "bsp_button.h"
#include "bsp_display.h"
#include "bsp_i2c.h"
#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "message_store.h"
#include "nvs.h"
#include "nvs_flash.h"
#include <stdio.h>
#include <string.h>

extern const lv_font_t font_chinese_16;
static const char *TAG = "passport_notify";

#define SCREEN_BRIGHTNESS_PERCENT 85
#define SCREEN_TIMEOUT_MS 60000
#define WAKE_INPUT_SUPPRESS_MS (BSP_BTN_LONG_PRESS_MS + 1000)
static lv_obj_t *s_message_label, *s_link_label, *s_alert_label;
static lv_obj_t *s_source_label, *s_counter_label, *s_icon_box, *s_icon_label;
static lv_obj_t *s_card, *s_help_label, *s_toast_box;
static lv_obj_t *s_audio_icon_box, *s_audio_icon_label;
static message_store_t s_messages;
static uint32_t s_overlay_until, s_hint_until;
static uint32_t s_last_code = UINT32_MAX;
static bool s_overlay_active, s_hint_active, s_clear_confirmation;
static bool s_muted, s_audio_ready, s_nvs_ready;
static bool s_portal_active, s_portal_selection;
static bool s_screen_off, s_wake_input_pending;
static bsp_btn_t s_wake_button;
static uint32_t s_screen_deadline, s_wake_suppress_until;
static app_firmware_state_t s_last_firmware_state = APP_FIRMWARE_IDLE;
static uint8_t s_last_firmware_progress = UINT8_MAX;
static esp_err_t s_ble_error;

static lv_obj_t *label(lv_obj_t *parent, const char *text, int x, int y,
                       int w, int h, uint32_t color, lv_text_align_t align)
{
    lv_obj_t *object = lv_label_create(parent);
    lv_label_set_text(object, text);
    lv_label_set_long_mode(object, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(object, x, y);
    lv_obj_set_size(object, w, h);
    lv_obj_set_style_text_font(object, &font_chinese_16, 0);
    lv_obj_set_style_text_color(object, lv_color_hex(color), 0);
    lv_obj_set_style_text_align(object, align, 0);
    return object;
}

static lv_obj_t *panel(lv_obj_t *parent, int x, int y, int w, int h,
                       int radius, uint32_t color)
{
    lv_obj_t *object = lv_obj_create(parent);
    lv_obj_set_pos(object, x, y);
    lv_obj_set_size(object, w, h);
    lv_obj_set_style_bg_color(object, lv_color_hex(color), 0);
    lv_obj_set_style_radius(object, radius, 0);
    lv_obj_set_style_border_width(object, 0, 0);
    lv_obj_set_style_pad_all(object, 0, 0);
    lv_obj_set_scrollable(object, false);
    return object;
}

static bool deadline_reached(uint32_t now, uint32_t deadline)
{
    return (int32_t)(now - deadline) >= 0;
}

static void screen_mark_activity(void)
{
    s_screen_deadline = lv_tick_get() + SCREEN_TIMEOUT_MS;
}

static void screen_wake(void)
{
    screen_mark_activity();
    if (!s_screen_off) return;

    esp_err_t error = bsp_display_set_enabled(true);
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "display wake failed: %s", esp_err_to_name(error));
    }
    bsp_display_backlight(SCREEN_BRIGHTNESS_PERCENT);
    s_screen_off = false;
}

static void screen_sleep(void)
{
    if (s_screen_off) return;

    bsp_display_backlight(0);
    esp_err_t error = bsp_display_set_enabled(false);
    if (error != ESP_OK) {
        ESP_LOGW(TAG, "display sleep failed: %s", esp_err_to_name(error));
    }
    s_screen_off = true;
    s_wake_input_pending = false;
}

static const char *message_category(uint8_t type)
{
    if (type == 1) return "短信通知";
    if (type == 2) return "应用通知";
    return "连接提示";
}

static void set_source_badge(uint8_t type, const char *source)
{
    const char *glyph = type == 1 ? "信" : "通";
    uint32_t color = type == 1 ? 0x2F80ED : 0x1F9D8A;

    if (source && (strstr(source, "微信") || strstr(source, "WeChat"))) {
        glyph = "微";
        color = 0x07C160;
    } else if (source && (strstr(source, "飞书") || strstr(source, "Lark"))) {
        glyph = "飞";
        color = 0x3370FF;
    } else if (source && (strstr(source, "短信") || strstr(source, "信息") ||
                          strstr(source, "Messages"))) {
        glyph = "信";
        color = 0x2F80ED;
    }

    lv_label_set_text(s_icon_label, glyph);
    lv_obj_set_style_bg_color(s_icon_box, lv_color_hex(color), 0);
}

static void set_content_mode(bool has_content)
{
    lv_obj_set_height(s_card, has_content ? 274 : 236);
    lv_obj_set_height(s_message_label, has_content ? 192 : 154);
    lv_obj_set_hidden(s_help_label, has_content);
}

static void show_current_message(void)
{
    const app_message_t *message = message_store_current(&s_messages);
    if (!message) {
        set_content_mode(false);
        lv_label_set_text(s_source_label, "等待手机通知");
        lv_label_set_text(s_counter_label, "0");
        lv_label_set_text(s_icon_label, "等");
        lv_obj_set_style_bg_color(s_icon_box, lv_color_hex(0x31506A), 0);
        lv_label_set_text(s_message_label,
                          "微信、飞书和短信通知\n会保留在这里。");
        return;
    }

    set_content_mode(true);
    const char *body = message->text;
    const char *newline = strchr(message->text, '\n');
    char source[64];
    if (newline) {
        size_t source_length = (size_t)(newline - message->text);
        if (source_length >= sizeof(source)) source_length = sizeof(source) - 1;
        memcpy(source, message->text, source_length);
        source[source_length] = '\0';
        body = newline + 1;
    } else {
        snprintf(source, sizeof(source), "%s", message_category(message->type));
    }
    if (*body == '\0') body = message->text;

    char counter[24];
    snprintf(counter, sizeof(counter), "%u/%u",
             (unsigned)message_store_current_number(&s_messages),
             (unsigned)message_store_count(&s_messages));
    lv_label_set_text(s_source_label, source);
    lv_label_set_text(s_counter_label, counter);
    lv_label_set_text(s_message_label, body);
    set_source_badge(message->type, source);
}

static void show_portal(void)
{
    set_content_mode(true);
    lv_label_set_text(s_source_label, "玩法门户");
    lv_label_set_text(s_icon_label, s_portal_selection ? "玩" : "门");
    lv_obj_set_style_bg_color(s_icon_box,
                              lv_color_hex(s_portal_selection ? 0x7C5CE0 : 0x1F9D8A), 0);
    lv_label_set_text(s_counter_label, s_portal_selection ? "2/2" : "1/2");

    if (!s_portal_selection) {
        lv_label_set_text(s_message_label,
                          "随身消息\n当前常驻门户\n\n上/下：切换玩法\nOK：返回消息");
        return;
    }

    if (app_firmware_slot_present()) {
        char text[240];
        snprintf(text, sizeof(text), "%s\n已安装到设备玩法槽\n\n长按 OK 3 秒启动\n重启后返回门户",
                 app_firmware_slot_name());
        lv_label_set_text(s_message_label, text);
    } else {
        lv_label_set_text(s_message_label,
                          "自定义玩法槽\n当前为空\n\n请在手机门户中\n导入并安装 .bin 固件");
    }
}

static void show_firmware_transfer(uint8_t progress)
{
    set_content_mode(true);
    lv_label_set_text(s_source_label, "正在安装玩法");
    lv_label_set_text(s_icon_label, "装");
    lv_obj_set_style_bg_color(s_icon_box, lv_color_hex(0x3370FF), 0);
    lv_label_set_text_fmt(s_counter_label, "%u%%", (unsigned)progress);
    lv_label_set_text_fmt(s_message_label,
                          "%s\n\n正在通过安全蓝牙写入\n请保持手机靠近设备\n不要关闭应用或断电",
                          app_firmware_slot_name());
}

static void restore_primary_view(void)
{
    if (app_firmware_busy()) show_firmware_transfer(app_firmware_progress());
    else if (s_portal_active) show_portal();
    else show_current_message();
}

static void show_overlay(const char *category, const char *text, uint32_t duration_ms)
{
    s_overlay_active = true;
    s_overlay_until = lv_tick_get() + duration_ms;
    set_content_mode(true);
    lv_label_set_text(s_source_label, category);
    lv_label_set_text(s_counter_label, "");
    lv_label_set_text(s_icon_label, "!");
    lv_obj_set_style_bg_color(s_icon_box, lv_color_hex(0xD9772B), 0);
    lv_label_set_text(s_message_label, text);
}

static void cancel_overlay(void)
{
    s_overlay_active = false;
    s_overlay_until = 0;
}

static void update_audio_status(void)
{
    lv_label_set_text(s_audio_icon_label,
                      s_muted ? LV_SYMBOL_MUTE : LV_SYMBOL_VOLUME_MAX);
    lv_obj_set_style_bg_color(s_audio_icon_box,
                              lv_color_hex(s_muted ? 0x596674 : 0x1F9D8A), 0);
}

static void show_hint(const char *text, uint32_t duration_ms)
{
    s_hint_active = true;
    s_hint_until = lv_tick_get() + duration_ms;
    lv_label_set_text(s_alert_label, text);
    lv_obj_set_hidden(s_toast_box, false);
    lv_obj_move_foreground(s_toast_box);
}

static void load_preferences(void)
{
    if (!s_nvs_ready) return;
    nvs_handle_t handle;
    if (nvs_open("notify_ui", NVS_READONLY, &handle) != ESP_OK) return;
    uint8_t muted = 0;
    if (nvs_get_u8(handle, "muted", &muted) == ESP_OK) s_muted = muted != 0;
    nvs_close(handle);
}

static void save_muted_preference(void)
{
    if (!s_nvs_ready) return;
    nvs_handle_t handle;
    if (nvs_open("notify_ui", NVS_READWRITE, &handle) != ESP_OK) return;
    if (nvs_set_u8(handle, "muted", s_muted ? 1 : 0) == ESP_OK) nvs_commit(handle);
    nvs_close(handle);
}

static void cancel_clear_confirmation(void)
{
    s_clear_confirmation = false;
    cancel_overlay();
    restore_primary_view();
}

static void tick(lv_timer_t *timer)
{
    (void)timer;
    uint32_t now = lv_tick_get();
    app_message_t message;
    while (app_ble_receive(&message)) {
        screen_wake();
        if (message.type == 3) {
            if (!s_portal_active && !app_firmware_busy()) {
                s_clear_confirmation = false;
                show_overlay("连接提示", message.text, 3500);
            }
            continue;
        }

        bool dropped_oldest = message_store_push(&s_messages, &message);
        s_clear_confirmation = false;
        if (!s_portal_active && !app_firmware_busy()) {
            cancel_overlay();
            show_current_message();
        }
        show_hint(dropped_oldest ? "消息已满 · 已移除最早一条" : "收到新消息",
                  dropped_oldest ? 2200 : 1200);
        if (s_audio_ready && !s_muted) app_alert_play();
    }

    app_firmware_state_t firmware_state = app_firmware_state();
    uint8_t firmware_progress = app_firmware_progress();
    if (firmware_state == APP_FIRMWARE_RECEIVING) {
        screen_wake();
        if (firmware_state != s_last_firmware_state ||
            firmware_progress != s_last_firmware_progress) {
            cancel_overlay();
            show_firmware_transfer(firmware_progress);
        }
    } else if (s_last_firmware_state == APP_FIRMWARE_RECEIVING) {
        s_portal_active = true;
        s_portal_selection = true;
        cancel_overlay();
        show_portal();
        show_hint(firmware_state == APP_FIRMWARE_READY ?
                  "玩法安装完成" : "安装失败 · 请在手机重试", 2600);
    }
    s_last_firmware_state = firmware_state;
    s_last_firmware_progress = firmware_progress;

    if (s_overlay_active && (int32_t)(now - s_overlay_until) >= 0) {
        s_clear_confirmation = false;
        cancel_overlay();
        restore_primary_view();
    }
    if (s_hint_active && (int32_t)(now - s_hint_until) >= 0) {
        s_hint_active = false;
        s_hint_until = 0;
        lv_obj_set_hidden(s_toast_box, true);
    }

    uint32_t code = app_ble_passkey();
    if (code != s_last_code) {
        s_last_code = code;
        if (code != UINT32_MAX) {
            screen_wake();
            lv_label_set_text_fmt(s_link_label, "配对 %06lu", (unsigned long)code);
        }
    }
    if (code == UINT32_MAX) {
        lv_label_set_text(s_link_label,
                          s_ble_error != ESP_OK ? "蓝牙失败" :
                          app_ble_authenticated() ? "安全配对" :
                          app_ble_connected() ? "待配对" :
                          "等待连接");
    } else {
        screen_mark_activity();
    }

    if (!s_screen_off && !app_firmware_busy() && code == UINT32_MAX &&
        deadline_reached(now, s_screen_deadline)) {
        screen_sleep();
    }
}

static void restart_into_user_firmware(void *argument)
{
    (void)argument;
    vTaskDelay(pdMS_TO_TICKS(650));
    esp_restart();
}

static void launch_selected_firmware(void)
{
    if (!s_portal_selection) {
        s_portal_active = false;
        show_current_message();
        show_hint("已在随身消息门户", 1400);
        return;
    }
    if (!app_firmware_slot_present()) {
        show_hint("玩法槽为空 · 请先用手机安装", 2200);
        return;
    }
    esp_err_t error = app_firmware_prepare_boot();
    if (error != ESP_OK) {
        ESP_LOGE(TAG, "prepare firmware boot failed: %s", esp_err_to_name(error));
        show_hint("玩法启动失败", 1800);
        return;
    }
    show_overlay("玩法门户", "正在启动自定义玩法…\n重新开机可返回门户", 10000);
    if (xTaskCreate(restart_into_user_firmware, "launch_fw", 2048, NULL, 5, NULL) != pdPASS) {
        ESP_LOGE(TAG, "failed to create firmware restart task");
        esp_restart();
    }
}

static void on_key(bsp_btn_t button, bsp_btn_ev_t event, void *user)
{
    (void)user;
    if (!bsp_lvgl_lock(200)) return;

    uint32_t now = lv_tick_get();
    if (s_screen_off) {
        screen_wake();
        if (event == BSP_BTN_PRESS) {
            s_wake_input_pending = true;
            s_wake_button = button;
            s_wake_suppress_until = now + WAKE_INPUT_SUPPRESS_MS;
        }
        bsp_lvgl_unlock();
        return;
    }

    if (s_wake_input_pending) {
        if (deadline_reached(now, s_wake_suppress_until)) {
            s_wake_input_pending = false;
        } else if (button == s_wake_button) {
            if (event != BSP_BTN_PRESS) s_wake_input_pending = false;
            bsp_lvgl_unlock();
            return;
        }
    }
    screen_mark_activity();

    if (app_firmware_busy()) {
        if (event == BSP_BTN_CLICK || event == BSP_BTN_LONG) {
            show_hint("正在安装 · 请勿操作", 1400);
        }
        bsp_lvgl_unlock();
        return;
    }

    if (event == BSP_BTN_DOUBLE && button == BSP_BTN_DOWN) {
        screen_sleep();
        bsp_lvgl_unlock();
        return;
    }

    if (event == BSP_BTN_DOUBLE && button == BSP_BTN_OK) {
        s_clear_confirmation = false;
        cancel_overlay();
        s_portal_active = !s_portal_active;
        if (s_portal_active) show_portal();
        else show_current_message();
        bsp_lvgl_unlock();
        return;
    }

    if (s_portal_active) {
        if (event == BSP_BTN_CLICK) {
            if (button == BSP_BTN_UP || button == BSP_BTN_DOWN) {
                s_portal_selection = !s_portal_selection;
                show_portal();
            } else if (button == BSP_BTN_OK) {
                s_portal_active = false;
                show_current_message();
            }
        } else if (event == BSP_BTN_LONG && button == BSP_BTN_OK) {
            launch_selected_firmware();
        }
        bsp_lvgl_unlock();
        return;
    }

    if (event == BSP_BTN_CLICK) {
        if (s_clear_confirmation) {
            if (button == BSP_BTN_OK) {
                s_clear_confirmation = false;
                message_store_clear(&s_messages);
                show_overlay("消息管理", "全部消息已清空", 1800);
            } else {
                cancel_clear_confirmation();
                show_hint("已取消清空", 1200);
            }
            bsp_lvgl_unlock();
            return;
        }

        cancel_overlay();
        if (button == BSP_BTN_UP) {
            if (message_store_count(&s_messages) == 0) show_hint("暂无消息", 1200);
            else if (!message_store_previous(&s_messages)) show_hint("已经是第一条", 1200);
            show_current_message();
        } else if (button == BSP_BTN_DOWN) {
            if (message_store_count(&s_messages) == 0) show_hint("暂无消息", 1200);
            else if (!message_store_next(&s_messages)) show_hint("已经是最新一条", 1200);
            show_current_message();
        } else if (button == BSP_BTN_OK) {
            if (message_store_dismiss_current(&s_messages)) {
                show_current_message();
                char hint[40];
                snprintf(hint, sizeof(hint), "已处理 · 剩余 %u 条",
                         (unsigned)message_store_count(&s_messages));
                show_hint(hint, 1600);
            } else {
                show_hint("暂无可处理消息", 1200);
            }
        }
    } else if (event == BSP_BTN_LONG) {
        if (button == BSP_BTN_UP) {
            if (message_store_count(&s_messages) == 0) {
                show_hint("暂无消息", 1200);
            } else {
                s_clear_confirmation = true;
                show_overlay("确认清空全部？", "短按 OK：确认清空\n按上/下键：取消", 6000);
            }
        } else if (button == BSP_BTN_DOWN) {
            if (s_clear_confirmation) cancel_clear_confirmation();
            s_muted = !s_muted;
            app_alert_set_enabled(!s_muted);
            save_muted_preference();
            update_audio_status();
            show_hint(s_muted ? "已静音" : "提示音已恢复", 1200);
        } else if (button == BSP_BTN_OK) {
            s_clear_confirmation = false;
            show_overlay("设备自检",
                         s_muted ? "屏幕与按键正常 · 当前静音" :
                                   "屏幕、按键与提示音正常",
                         5000);
            if (s_audio_ready && !s_muted) app_alert_play();
        }
    }

    bsp_lvgl_unlock();
}

static void build_ui(void)
{
    lv_obj_t *screen = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x0B1219), 0);
    lv_obj_set_style_border_width(screen, 0, 0);
    lv_obj_set_style_pad_all(screen, 0, 0);
    lv_obj_set_scrollable(screen, false);

    label(screen, "随身消息", 10, 7, 82, 24, 0xF3F6FA, LV_TEXT_ALIGN_LEFT);
    s_link_label = label(screen, "等待连接", 92, 7, 104, 24,
                         0x7EB8E0, LV_TEXT_ALIGN_RIGHT);
    s_audio_icon_box = panel(screen, 202, 4, 28, 28, 14, 0x1F9D8A);
    s_audio_icon_label = label(s_audio_icon_box, LV_SYMBOL_VOLUME_MAX,
                               0, 5, 28, 18, 0xFFFFFF, LV_TEXT_ALIGN_CENTER);
    lv_obj_set_style_text_font(s_audio_icon_label, LV_FONT_DEFAULT, 0);

    s_card = panel(screen, 8, 38, 224, 236, 14, 0x182531);
    s_icon_box = panel(s_card, 12, 12, 38, 38, 10, 0x31506A);
    s_icon_label = label(s_icon_box, "等", 0, 8, 38, 22, 0xFFFFFF,
                         LV_TEXT_ALIGN_CENTER);
    s_source_label = label(s_card, "等待手机通知", 60, 8, 106, 44, 0xF6F8FA,
                           LV_TEXT_ALIGN_LEFT);
    lv_obj_t *counter_box = panel(s_card, 172, 16, 40, 24, 12, 0x263A49);
    s_counter_label = label(counter_box, "0", 0, 2, 40, 20, 0x88D9C2,
                            LV_TEXT_ALIGN_CENTER);
    panel(s_card, 12, 59, 200, 1, 0, 0x304453);
    s_message_label = label(s_card,
                            "微信、飞书和短信通知\n会保留在这里。",
                            12, 70, 200, 154, 0xFFFFFF, LV_TEXT_ALIGN_LEFT);
    lv_obj_set_style_text_line_space(s_message_label, 4, 0);

    s_help_label = label(screen,
                         "上/下翻阅 · OK处理 · 双击OK门户\n双击下键熄屏 · 长按下键静音",
                         8, 278, 224, 40, 0x88A5B8, LV_TEXT_ALIGN_CENTER);
    lv_obj_set_style_text_line_space(s_help_label, 2, 0);

    s_toast_box = panel(screen, 18, 280, 204, 30, 15, 0x263A49);
    s_alert_label = label(s_toast_box, "", 6, 5, 192, 20, 0xF3F6FA,
                          LV_TEXT_ALIGN_CENTER);
    lv_obj_set_hidden(s_toast_box, true);
    update_audio_status();
    lv_screen_load(screen);
    lv_timer_create(tick, 200, NULL);
}

void app_main(void)
{
    message_store_init(&s_messages);
    bsp_i2c_init();
    if (bsp_display_init() != ESP_OK || !bsp_lvgl_init()) {
        ESP_LOGE(TAG, "display init failed");
        return;
    }
    bsp_display_backlight(SCREEN_BRIGHTNESS_PERCENT);
    screen_mark_activity();

    esp_err_t nvs_error = nvs_flash_init();
    s_nvs_ready = nvs_error == ESP_OK;
    if (!s_nvs_ready) ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(nvs_error));
    load_preferences();
    app_firmware_init();
    s_last_firmware_state = app_firmware_state();

    s_audio_ready = bsp_audio_init() == ESP_OK;
    if (s_audio_ready) {
        app_alert_start();
        app_alert_set_enabled(!s_muted);
    }
    if (bsp_lvgl_lock(1000)) {
        build_ui();
        bsp_lvgl_unlock();
    }
    if (s_nvs_ready) s_ble_error = app_ble_start();
    else s_ble_error = nvs_error;
    if (s_ble_error != ESP_OK) {
        ESP_LOGE(TAG, "BLE start failed: %s", esp_err_to_name(s_ble_error));
    }
    esp_err_t error = bsp_button_init(on_key, NULL);
    if (error != ESP_OK) ESP_LOGE(TAG, "button init failed: %s", esp_err_to_name(error));
}
