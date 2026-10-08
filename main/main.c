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
#include "nvs_flash.h"
#include <stdio.h>

extern const lv_font_t font_chinese_16;
static const char *TAG = "passport_notify";
static lv_obj_t *s_message_label, *s_link_label, *s_alert_label, *s_category_label;
static message_store_t s_messages;
static uint32_t s_overlay_until;
static uint32_t s_last_code = UINT32_MAX;
static bool s_overlay_active, s_muted, s_audio_ready;
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
        lv_label_set_text(s_category_label, "最新消息");
        lv_label_set_text(s_message_label, "等待手机通知...");
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
                      s_muted ? "静音 · 长按下键恢复" : "声音开 · 长按下键静音");
}

static void tick(lv_timer_t *timer)
{
    (void)timer;
    uint32_t now = lv_tick_get();
    app_message_t message;
    while (app_ble_receive(&message)) {
        // Sensitive messages remain only in this bounded RAM history. They are
        // never logged or persisted, and are removed only by explicit controls.
        message_store_push(&s_messages, &message);
        cancel_overlay();
        show_current_message();
        if (s_audio_ready && !s_muted) app_alert_play();
    }

    if (s_overlay_active && (int32_t)(now - s_overlay_until) >= 0) {
        cancel_overlay();
        show_current_message();
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
        cancel_overlay();
        if (btn == BSP_BTN_UP) {
            message_store_previous(&s_messages);
            show_current_message();
        } else if (btn == BSP_BTN_DOWN) {
            message_store_next(&s_messages);
            show_current_message();
        } else if (btn == BSP_BTN_OK) {
            message_store_dismiss_current(&s_messages);
            show_current_message();
        }
    } else if (ev == BSP_BTN_LONG) {
        if (btn == BSP_BTN_UP) {
            message_store_clear(&s_messages);
            show_overlay("消息管理", "全部消息已清除", 2500);
        } else if (btn == BSP_BTN_DOWN) {
            s_muted = !s_muted;
            app_alert_set_enabled(!s_muted);
            update_audio_status();
        } else if (btn == BSP_BTN_OK) {
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
    s_category_label = label(box, "最新消息", 14, 14, 188, 27, 0x88D9C2,
                             LV_TEXT_ALIGN_LEFT);
    s_message_label = label(box, "等待手机通知...", 14, 51, 188, 105, 0xFFFFFF,
                            LV_TEXT_ALIGN_LEFT);
    s_alert_label = label(scr, "声音开 · 长按下键静音", 8, 269, 224, 22, 0x95AFC2,
                          LV_TEXT_ALIGN_CENTER);
    label(scr, "短按 上前 下后 OK删除", 6, 295, 228, 22, 0x95AFC2,
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
    // BLE bonding needs NVS. Do not erase any existing device data on failure.
    esp_err_t nvs_error = nvs_flash_init();
    if (nvs_error != ESP_OK) ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(nvs_error));
    s_audio_ready = bsp_audio_init() == ESP_OK;
    if (s_audio_ready) app_alert_start();
    if (bsp_lvgl_lock(1000)) {
        build_ui();
        bsp_lvgl_unlock();
    }
    if (nvs_error == ESP_OK) s_ble_error = app_ble_start();
    else s_ble_error = nvs_error;
    if (s_ble_error != ESP_OK) ESP_LOGE(TAG, "BLE start failed: %s", esp_err_to_name(s_ble_error));
    esp_err_t err = bsp_button_init(on_key, NULL);
    if (err != ESP_OK) ESP_LOGE(TAG, "button init failed: %s", esp_err_to_name(err));
}
