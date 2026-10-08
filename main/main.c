// AI Passport: a private Android notification display.
#include "app_alert.h"
#include "app_ble.h"
#include "bsp_audio.h"
#include "bsp_button.h"
#include "bsp_display.h"
#include "bsp_i2c.h"
#include "esp_log.h"
#include "lvgl.h"
#include "message_store.h"
#include "nvs.h"
#include "nvs_flash.h"
#include <stdio.h>

extern const lv_font_t font_chinese_16;
static const char *TAG = "passport_notify";
static lv_obj_t *s_message_label, *s_link_label, *s_alert_label, *s_category_label;
static message_store_t s_messages;
static uint32_t s_overlay_until, s_hint_until;
static uint32_t s_last_code = UINT32_MAX;
static bool s_overlay_active, s_hint_active, s_clear_confirmation;
static bool s_muted, s_audio_ready, s_nvs_ready;
static esp_err_t s_ble_error;

static lv_obj_t *label(lv_obj_t *parent, const char *text, int x, int y,
                       int w, int h, uint32_t color, lv_text_align_t align)
{
    lv_obj_t *obj = lv_label_create(parent);
    lv_label_set_text(obj, text);
    lv_label_set_long_mode(obj, LV_LABEL_LONG_WRAP);
    lv_obj_set_pos(obj, x, y);
    lv_obj_set_size(obj, w, h);
    lv_obj_set_style_text_font(obj, &font_chinese_16, 0);
    lv_obj_set_style_text_color(obj, lv_color_hex(color), 0);
    lv_obj_set_style_text_align(obj, align, 0);
    return obj;
}

static lv_obj_t *panel(lv_obj_t *parent, int x, int y, int w, int h,
                       int radius, uint32_t color)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_set_pos(obj, x, y);
    lv_obj_set_size(obj, w, h);
    lv_obj_set_style_bg_color(obj, lv_color_hex(color), 0);
    lv_obj_set_style_radius(obj, radius, 0);
    lv_obj_set_style_border_width(obj, 0, 0);
    lv_obj_set_style_pad_all(obj, 0, 0);
    lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
    return obj;
}

static const char *message_category(uint8_t type)
{
    if (type == 1) return "短信通知";
    if (type == 2) return "应用通知";
    return "连接提示";
}

static void show_current_message(void)
{
    const app_message_t *message = message_store_current(&s_messages);
    if (!message) {
        lv_label_set_text(s_category_label, "消息中心 · 0");
        lv_label_set_text(s_message_label, "暂无消息\n等待手机通知...");
        return;
    }

    char category[48];
    snprintf(category, sizeof(category), "%s · %u/%u", message_category(message->type),
             (unsigned)message_store_current_number(&s_messages),
             (unsigned)message_store_count(&s_messages));
    lv_label_set_text(s_category_label, category);
    lv_label_set_text(s_message_label, message->text);
}

static void show_overlay(const char *category, const char *text, uint32_t duration_ms)
{
    s_overlay_active = true;
    s_overlay_until = lv_tick_get() + duration_ms;
    lv_label_set_text(s_category_label, category);
    lv_label_set_text(s_message_label, text);
}

static void cancel_overlay(void)
{
    s_overlay_active = false;
    s_overlay_until = 0;
}

static void update_audio_status(void)
{
    lv_label_set_text(s_alert_label,
                      s_muted ? "已静音 · 长按下键恢复" : "提示音开 · 长按下键静音");
}

static void show_hint(const char *text, uint32_t duration_ms)
{
    s_hint_active = true;
    s_hint_until = lv_tick_get() + duration_ms;
    lv_label_set_text(s_alert_label, text);
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
    show_current_message();
}

static void tick(lv_timer_t *timer)
{
    (void)timer;
    uint32_t now = lv_tick_get();
    app_message_t message;
    while (app_ble_receive(&message)) {
        // Connection state is transient UI, not an inbox item. This prevents
        // reconnects from displacing real phone notifications.
        if (message.type == 3) {
            s_clear_confirmation = false;
            show_overlay("连接提示", message.text, 3500);
            continue;
        }

        // Sensitive messages remain only in this bounded RAM history. They are
        // never logged or persisted, and are removed only by explicit controls.
        bool dropped_oldest = message_store_push(&s_messages, &message);
        s_clear_confirmation = false;
        cancel_overlay();
        show_current_message();
        if (dropped_oldest) {
            show_hint("消息已满 · 已移除最早一条", 2200);
        } else {
            show_hint("收到新消息", 1200);
        }
        if (s_audio_ready && !s_muted) app_alert_play();
    }

    if (s_overlay_active && (int32_t)(now - s_overlay_until) >= 0) {
        s_clear_confirmation = false;
        cancel_overlay();
        show_current_message();
    }
    if (s_hint_active && (int32_t)(now - s_hint_until) >= 0) {
        s_hint_active = false;
        s_hint_until = 0;
        update_audio_status();
    }

    uint32_t code = app_ble_passkey();
    if (code != s_last_code) {
        s_last_code = code;
        if (code != UINT32_MAX) {
            lv_label_set_text_fmt(s_link_label, "配对码 %06lu · 请在手机输入",
                                  (unsigned long)code);
        }
    }
    if (code == UINT32_MAX) {
        lv_label_set_text(s_link_label, s_ble_error != ESP_OK ? "蓝牙启动失败" :
                          app_ble_authenticated() ? "手机已安全配对" :
                          app_ble_connected() ? "手机已连接 · 等待配对" :
                          "等待连接 · PassportNotify");
    }
}

static void on_key(bsp_btn_t btn, bsp_btn_ev_t ev, void *user)
{
    (void)user;
    if (!bsp_lvgl_lock(200)) return;

    if (ev == BSP_BTN_CLICK) {
        if (s_clear_confirmation) {
            if (btn == BSP_BTN_OK) {
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
        if (btn == BSP_BTN_UP) {
            if (message_store_count(&s_messages) == 0) show_hint("暂无消息", 1200);
            else if (!message_store_previous(&s_messages)) show_hint("已经是第一条", 1200);
            show_current_message();
        } else if (btn == BSP_BTN_DOWN) {
            if (message_store_count(&s_messages) == 0) show_hint("暂无消息", 1200);
            else if (!message_store_next(&s_messages)) show_hint("已经是最新一条", 1200);
            show_current_message();
        } else if (btn == BSP_BTN_OK) {
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
    } else if (ev == BSP_BTN_LONG) {
        if (btn == BSP_BTN_UP) {
            if (message_store_count(&s_messages) == 0) {
                show_hint("暂无消息", 1200);
            } else {
                s_clear_confirmation = true;
                show_overlay("确认清空全部？", "短按 OK：确认清空\n按上/下键：取消", 6000);
            }
        } else if (btn == BSP_BTN_DOWN) {
            if (s_clear_confirmation) cancel_clear_confirmation();
            s_muted = !s_muted;
            app_alert_set_enabled(!s_muted);
            save_muted_preference();
            s_hint_active = false;
            update_audio_status();
        } else if (btn == BSP_BTN_OK) {
            s_clear_confirmation = false;
            show_overlay("设备自检",
                         s_muted ? "屏幕与按键正常 · 当前静音" : "屏幕、按键与提示音正常",
                         5000);
            if (s_audio_ready && !s_muted) app_alert_play();
        }
    }

    bsp_lvgl_unlock();
}

static void build_ui(void)
{
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_set_style_bg_color(scr, lv_color_hex(0x101923), 0);
    lv_obj_set_style_border_width(scr, 0, 0);
    lv_obj_set_style_pad_all(scr, 0, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
    label(scr, "随身消息", 16, 13, 208, 27, 0xF3F6FA, LV_TEXT_ALIGN_LEFT);
    s_link_label = label(scr, "等待连接 · PassportNotify", 16, 45, 208, 40,
                         0x94C5E8, LV_TEXT_ALIGN_LEFT);
    lv_obj_t *box = panel(scr, 12, 93, 216, 170, 13, 0x243443);
    s_category_label = label(box, "消息中心 · 0", 14, 14, 188, 27, 0x88D9C2,
                             LV_TEXT_ALIGN_LEFT);
    s_message_label = label(box, "暂无消息\n等待手机通知...", 14, 51, 188, 105, 0xFFFFFF,
                            LV_TEXT_ALIGN_LEFT);
    s_alert_label = label(scr, "", 8, 269, 224, 22, 0x95AFC2,
                          LV_TEXT_ALIGN_CENTER);
    update_audio_status();
    label(scr, "短按 上前 下后 OK处理", 6, 295, 228, 22, 0x95AFC2,
          LV_TEXT_ALIGN_CENTER);
    lv_screen_load(scr);
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
    bsp_display_backlight(85);
    // BLE bonding and the non-sensitive mute preference need NVS. Do not erase
    // any existing device data if NVS initialization fails.
    esp_err_t nvs_error = nvs_flash_init();
    s_nvs_ready = nvs_error == ESP_OK;
    if (!s_nvs_ready) ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(nvs_error));
    load_preferences();
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
    if (s_ble_error != ESP_OK) ESP_LOGE(TAG, "BLE start failed: %s", esp_err_to_name(s_ble_error));
    esp_err_t err = bsp_button_init(on_key, NULL);
    if (err != ESP_OK) ESP_LOGE(TAG, "button init failed: %s", esp_err_to_name(err));
}
